package matching.monad

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import Monad._
import Tree._

class TreeSpec extends AnyFlatSpec with Matchers {
  "bind" should "calculate bind" in {
    val t: Tree[Int] = Or(Leaf(1), Or(Fail, Leaf(2)))
    (t >>= (i => Or(Leaf(i), Leaf(i*2)))) should be (
      Or(Or(Leaf(1), Leaf(2)), Or(Fail, Or(Leaf(2), Leaf(4))))
    )
  }

  "flat" should "calculate list of leaf from left to right" in {
    val t: Tree[Int] = Or(Leaf(1), Or(Or(Leaf(2), Fail), Leaf(3)))
    flat(t) should be (Seq(1,2,3))
  }

  "hasSuccess" should "decide whether it contains Success" in {
    val t1: Tree[Int] = Or(Leaf(1), Or(Or(Leaf(2), Fail), Leaf(3)))
    hasSuccess(t1, Set(2)) should be (true)
    hasSuccess(t1) should be (false)

    val t2: Tree[Int] = Or(Leaf(1), Or(Or(Leaf(2), Success), Leaf(3)))
    hasSuccess(t2) should be (true)
  }

  "cut" should "remove redundant subtree" in {
    val t1: Tree[Int] = Or(Leaf(1), Or(Or(Leaf(2), Fail), Leaf(3)))
    cut(t1, Set(2,3)) should be (Or(Leaf(1), Lft(Lft(Leaf(2)))))
    cut(t1) should be (t1)

    val t2: Tree[Int] = Or(Leaf(1), Or(Or(Leaf(2), Success), Leaf(3)))
    cut(t2) should be (Or(Leaf(1), Lft(Or(Leaf(2), Success))))
  }
}
