(ns ^{:doc "Parsing facts."}
  midje.parsing.1-to-explicit-form.facts
  (:use midje.clojure.core
        midje.parsing.util.core
        midje.parsing.util.zip
        
        [midje.parsing.1-to-explicit-form.expects :only [expect?
                                            wrap-with-expect__then__at-rightmost-expect-leaf]]
        [midje.parsing.util.wrapping :only [already-wrapped?
                                              multiwrap
                                              with-additional-wrappers
                                              forms-to-wrap-around]]
        [midje.parsing.1-to-explicit-form.prerequisites :only [head-of-form-providing-prerequisites?
                                          insert-prerequisites-into-expect-form-as-fakes]]
        [midje.parsing.1-to-explicit-form.background :only [surround-with-background-fakes
                                       body-of-against-background
                                       against-background-contents-wrappers
                                       against-background-facts-and-checks-wrappers
                                       against-background?]]
        [midje.parsing.1-to-explicit-form.metaconstants :only [predefine-metaconstants-from-form]]
        [midje.util.laziness :only [eagerly]]
        [midje.parsing.util.zip :only [skip-to-rightmost-leaf]]
        [swiss-arrows.core :only [-<>]])
  (:require [clojure.zip :as zip])
  (:require [midje.util.pile :as pile]
            [midje.checking.facts :as fact-checking]
            [midje.data.compendium :as compendium]
            [midje.parsing.util.file-position :as position]
            [midje.parsing.util.arrows :as arrows]
            [midje.parsing.util.error-handling :as error]
            [midje.parsing.1-to-explicit-form.background :as background]
            [midje.parsing.1-to-explicit-form.future-facts :as parse-future-facts]
            [midje.parsing.1-to-explicit-form.metadata :as parse-metadata]
            [midje.parsing.2-to-lexical-maps.fakes :as parse-fakes]
            [midje.parsing.2-to-lexical-maps.examples :as parse-examples]
            [midje.parsing.2-to-lexical-maps.folded-fakes :as parse-folded-fakes]
            [midje.data.fact :as fact-data]))

(defn fact? [form]
  (or (first-named? form "fact")
      (first-named? form "facts")))

                                ;;; Fact processing

;; There are three stages to fact processing:
;; * Body processing: the convertion of arrow and provided forms into their
;;   semi-sweet forms, the insertion of background data, line numbering, etc.
;;   
;; * Compendium processing: wrapping the body in a function form that supports
;;   rerunning and history keeping.
;;   
;; * Load-time processing: wrapping the final form in code that does whatever
;;   should be done the first time the fact is loaded. (Such as running it for
;;   the first time.)

;;; Support code 

(def ^{:dynamic true} *parse-time-fact-level* 0)

(defn- working-on-top-level-fact? []
  (= *parse-time-fact-level* 1))
  
(defmacro given-possible-fact-nesting [& forms]
  `(binding [*parse-time-fact-level* (inc *parse-time-fact-level*)]
     ~@forms))

(defmacro working-on-nested-facts [& forms]
  ;; Make sure we don't treat this as a top-level fact
  `(binding [*parse-time-fact-level* (+ 2 *parse-time-fact-level*)]
     ~@forms))

;;; Body Processing

(defn to-semi-sweet
  "Convert sweet keywords into their semi-sweet equivalents.
   1) Arrow sequences become expect forms.
   2) (provided ...) become fakes inserted into preceding expect."
  [multi-form]
  (translate-zipper multi-form
    fact?
    skip-to-rightmost-leaf
                    
    arrows/start-of-checking-arrow-sequence?
    wrap-with-expect__then__at-rightmost-expect-leaf
    
    head-of-form-providing-prerequisites?
    insert-prerequisites-into-expect-form-as-fakes

    semi-sweet-keyword?
    skip-to-rightmost-leaf))

(declare midjcoexpand)

(defn expand-against-background [form]
  (background/assert-right-shape! form)
  (-<> form 
       body-of-against-background
       midjcoexpand
       (with-additional-wrappers (against-background-facts-and-checks-wrappers form) <>)
       (multiwrap <> (against-background-contents-wrappers form))))


(defn midjcoexpand
  "Descend form, macroexpanding *only* midje forms and placing background wrappers where appropriate."
  [form]
  (pred-cond form
    already-wrapped?     form
    quoted?              form
    parse-future-facts/future-fact?         (macroexpand form)
    against-background?  (expand-against-background form)
    expect?      (multiwrap form (forms-to-wrap-around :checks ))
    fact?        (macroexpand form)
    sequential?  (preserve-type form (eagerly (map midjcoexpand form)))
    :else        form))

(defn parse-expects [form]
  (translate-zipper form
     expect? (fn [loc]
               (zip/replace loc (parse-examples/to-lexical-map-form (zip/node loc))))))

(defn report-check-arrow-shape [form]
  (error/report-error form
                      (cl-format nil "    This form: ~A" form)
                      "... has the wrong shape. Expecting: (<actual> => <expected> [<keyword-value pairs>*])"))



(defn at-arrow__add-line-number-to-end__no-movement [number loc]
  (when (nil? (zip/right loc))
    (report-check-arrow-shape (position/positioned-form (zip/node (zip/up loc)) number)))
    
  (arrows/at-arrow__add-key-value-to-end__no-movement
   :position `(position/line-number-known ~number) loc))

(defn annotate-embedded-arrows-with-line-numbers [form]
  (translate-zipper form
    quoted?
    (comp skip-to-rightmost-leaf zip/down)

    (partial matches-symbols-in-semi-sweet-or-sweet-ns? arrows/all-arrows)
    #(at-arrow__add-line-number-to-end__no-movement (position/arrow-line-number %) %)))



(defn expand-fact-body [forms metadata]
  (-> forms
      annotate-embedded-arrows-with-line-numbers
      to-semi-sweet
      parse-folded-fakes/unfold-fakes
      surround-with-background-fakes
      midjcoexpand
      parse-expects
      (multiwrap (forms-to-wrap-around :facts))))


;;; Check-time processing

(defn wrap-with-check-time-code
  ([expanded-body metadata]
     (wrap-with-check-time-code expanded-body metadata
                                               (gensym 'this-function-here-)))

  ;; Having two versions lets tests not get stuck with a gensym.
  ([expanded-body metadata this-function-here-symbol]
     `(letfn [(base-function# [] ~expanded-body)
              (~this-function-here-symbol []
                (with-meta base-function#
                  (merge '~metadata
                         {:midje/top-level-fact? ~(working-on-top-level-fact?)})))]
        (~this-function-here-symbol))))

;;; Load-time processing

;; It's kind of annoying that the entire expansion is created, evaluated, 
;; and then thrown away. I think this is unavoidable if you want the
;; filter predicate to be applied in the repl.
(defn wrap-with-creation-time-code [function-form]
  (letfn [;; This form is a little awkward. It would be better to
          ;; write
          ;;     (if-let [fact ~function-form]
          ;;        (compendium/record-fact-existence! ...))
          ;; However, tabular expansion uses zip/seq-zip, so the
          ;; vector notation would confuse it.
          (wrap-with-creation-time-fact-recording [function-form]
            (if (working-on-top-level-fact?)
              `(compendium/record-fact-existence! ~function-form)
              function-form))
          
          (run-after-creation [function-form]
            `(fact-checking/creation-time-check ~function-form))]

    (predefine-metaconstants-from-form function-form)
    (-> function-form
        wrap-with-creation-time-fact-recording
        run-after-creation)))

;;; There could be validation here, but none has proven useful.

;;; Ta-da!

(defn complete-fact-transformation [metadata forms]
  (given-possible-fact-nesting
   (-> forms
       (expand-fact-body metadata)
       (wrap-with-check-time-code metadata)
       wrap-with-creation-time-code)))


(defn unparse-edited-fact
  "Uses a body and (parsed) metadata to make up a new `fact`.
   The resulting for has `:line` metadata."
  [metadata forms]
  (vary-meta `(midje.sweet/fact ~@(parse-metadata/unparse-metadata metadata) ~@forms)
             assoc :line (:midje/line metadata)))

  
  
