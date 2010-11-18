(ns clj-diff.miller
  "Algorithm from 'An O(NP) Sequence Comparison Algorithm' by
   Sun Wu, Udi Manber, Gene Myers and Web Miller.

   Please refer to the above paper while reading this code."
  (:require [clj-diff [optimisations :as opt]])
  (:require [clj-diff [myers :as myers]]))

(defn- next-x
  "Get the next farthest x value by looking at previous farthest values on the
  diagonal above and below diagonal k. Choose the greater of the farthest x on
  the above diagonal and the farthest x on the diagonal below plus one. fp is
  a map of p values to maps of diagonals => farthest points."
  [k fp]
  (max (inc (get fp (dec k) -1))
       (get fp (inc k) -1)))

(defn- snake
  "Starting at the farthest point on diagonal k, return the x value of the
  point at the end of the longest snake on this diagonal. A snake is a
  sequence of diagonal moves connecting match points on the edit graph."
  [a b n m k fp]
  (let [x (next-x k fp)
        y (- x k)]
    (myers/snake a b x y n m)))

(defn- search-p-band
  "Given a p value, search all diagonals in the p-band for the furthest
  reaching endpoints. Record the furthest reaching endpoint for each p value
  in the map fp. Returns an updated fp map for p."
  [a b n m delta p fp]
  (reduce (fn [fp next-k]
            (assoc fp next-k (snake a b n m next-k fp)))
          fp
          (concat (range (* -1 p) delta)
                  (reverse (range (inc delta) (+ delta (inc p))))
                  [delta])))

(defn ses
  "Find the size of the shortest edit script (ses). Returns a 3-tuple of the
  size of the ses, the delta value (which is the diagonal of the end point)
  and the fp map. The optimal path form source to sink can be constructed from
  this information."
  [a b]
  {:pre [(>= (count a) (count b))]}
  (let [n (dec (count a))
        m (dec (count b))
        delta (- n m)]
    (loop [p 0
           fp {}]
      (if (= (-> (get fp (dec p) {})
                 (get delta))
             n)
        [(dec p) delta fp]
        (recur (inc p)
               (assoc fp p
                      (search-p-band a b n m delta p (get fp (dec p) {}))))))))

(defn- edit-dist [delta p k]
  (if (> k delta)
    (+ (* 2 (- p (- k delta))) k)
    (+ (* 2 p) k)))

(defn- p-value-up [delta p k]
  (if (> (inc k) delta) p (dec p)))

(defn- p-value-left [delta p k]
  (if (< (dec k) delta) p (dec p)))

(defn- look-up [graph delta p x k]
  (when (> (- x k) 0)
    (let [up-k (inc k)
          up-p (p-value-up delta p k)
          x* (-> graph
                 (get up-p {})
                 (get up-k -1))]
      (when (and (>= x* 0) (= x x*))
        {:edit :insert
         :x x*
         :p up-p
         :k up-k
         :d (edit-dist delta up-p up-k)}))))

(defn- look-left [graph delta p x k]
  (when (> x 0)
    (let [left-k (dec k)
          left-p (p-value-left delta p k)
          x* (-> graph
                 (get left-p {})
                 (get left-k -1))]
      (when (and (>= x* 0) (= (dec x) x*))
        {:edit :delete
         :x x*
         :p left-p
         :k left-k
         :d (edit-dist delta left-p left-k)}))))

(defn- backtrack-snake
  "Find the x value of the start of the longest snake ending at (x, y)."
  [a b x y]
  {:pre [(and (>= x 0) (>= y 0))]}
  (loop [x x
         y y]
    (if (or (= x y 0) (not (= (get a x) (get b y))))
      x
      (recur (dec x) (dec y)))))

(defn- next-edit
  "Return the next move through the edit graph which will decrease the
  edit distance by 1."
  [a b graph delta p x k]
  {:post [(= (dec (edit-dist delta p k)) (:d %))]}
  (let [d (edit-dist delta p k)
        head-x (backtrack-snake a b x (- x k))]
    (loop [head-x head-x]
      (let [up (look-up graph delta p head-x k)
            left (look-left graph delta p head-x k)
            move (first (filter #(and (not (nil? %))
                                      (= (:d %) (dec d)))
                                [left up]))]
        (if (and (< head-x x) (nil? move))
          (recur (inc head-x))
          move)))))

(defn- edits
  "Return a sequence of edits. Each edit represents two points on the graph, a
  from and to point."
  [a b p delta graph]
  (let [next-fn (partial next-edit a b graph delta)]
    (loop [edits '()
           prev {:x (count a) :p p :k delta
                 :d (edit-dist delta p delta)}]
      (if (= (:d prev) 0)
        edits
        (let [next (next-fn (:p prev) (:x prev) (:k prev))]
          (recur (conj edits next) next))))))

(defn- diff*
  "Calculate the optimal path using the miller algorithm. This algorithm
  requires that a >= b."
  [a b]
  {:pre [(>= (count a) (count b))]}
  (apply edits a b (ses a b)))

(defn- transpose
  [edit]
  (-> edit
      (assoc :edit (if (= :insert (:edit edit)) :delete :insert))
      (assoc :x (- (:x edit) (:k edit)))
      (assoc :k (- (:k edit)))))

(defn- edits->script
  [b edits f]
  (reduce (fn [script edit]
            (let [{:keys [edit x k]} (f edit)
                  y (inc (- x k))
                  insertions (:+ script)
                  last-insert (last insertions)]
              (if (= edit :delete)
                (assoc script :- (conj (:- script) x))
                (assoc script :+ (let [index (dec x)]
                                   (if (= index (first last-insert))
                                     (conj (vec (butlast insertions))
                                           (conj last-insert (get b y)))
                                     (conj insertions [(dec x) (get b y)])))))))
          {:+ []
           :- []}
          edits))

(defn diff
  "Create an edit script that may be used to transform a into b. See doc string
  for clj-diff.core/diff. This function will ensure that diff* is called with
  arguments a and b where a >= b. If the passed values of a and b need to be
  swapped then the resulting path with will transposed."
  [a b]
  (opt/diff a b
            (fn [a b]
              (let [a (vec (cons nil a))
                    b (vec (cons nil b))
                    [a* b*] (if (> (count b) (count a)) [b a] [a b])
                    edits (diff* a* b*)]
                (edits->script b edits (if (= a* a) identity transpose))))))