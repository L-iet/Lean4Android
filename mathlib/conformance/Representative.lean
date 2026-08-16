import Mathlib.Analysis.SpecialFunctions.Pow.Real
import Mathlib.Data.Nat.Prime.Basic
import Mathlib.Tactic

example : Nat.Prime 7 := by norm_num

example (x : ℝ) (h : x = 2) : x ^ 2 = 4 := by norm_num [h]

#check Real.rpow_add
#eval ([1, 2, 3, 4].filter (· % 2 == 0))
