package matching.regexp

import scala.collection.mutable.Stack
import matching.monad._
import matching.monad.DMonad._
import matching.monad.DTree._
import matching.transition._
import matching.tool.{Analysis, Debug, IO}

trait RegExp[A] {
  override def toString(): String = RegExp.toString(this)
  def derive[M[_,_]](a: A, u: List[Option[A]])(implicit deriver: RegExpDeriver[M])
    : M[Option[RegExp[A]], Option[RegExp[A]]] = deriver.derive(this,u,Some(a))
  def derive[M[_,_]](a: Option[A], u: List[Option[A]])(implicit deriver: RegExpDeriver[M])
    : M[Option[RegExp[A]], Option[RegExp[A]]] = deriver.derive(this,u,a)
  def deriveEOL[M[_,_]](u: List[Option[A]])(implicit deriver: RegExpDeriver[M])
    : M[Unit, Unit] = deriver.deriveEOL(this,u)
}

case class ElemExp[A](a: A) extends RegExp[A]
case class EmptyExp[A]() extends RegExp[A]
case class EpsExp[A]() extends RegExp[A]
case class ConcatExp[A](r1: RegExp[A], r2: RegExp[A]) extends RegExp[A]
case class AltExp[A](r1: RegExp[A], r2: RegExp[A]) extends RegExp[A]
case class StarExp[A](r: RegExp[A], greedy: Boolean) extends RegExp[A]
case class PlusExp[A](r: RegExp[A], greedy: Boolean) extends RegExp[A]
case class OptionExp[A](r: RegExp[A], greedy: Boolean) extends RegExp[A]
case class RepeatExp[A](r: RegExp[A], var min: Option[Int], var max: Option[Int], greedy: Boolean) extends RegExp[A]
object RepeatExp {
  def apply[A](r: RegExp[A], min: Option[Int], max: Option[Int], greedy: Boolean): RegExp[A] = {
    def validate() {
      (min, max) match {
        case (Some(min),Some(max)) =>
          if (min < 0 || max < 0) {
            throw RegExp.InvalidRegExpException(s"invalid repeat expression: min and max must be positive")
          } else if (min > max) {
            throw RegExp.InvalidRegExpException(s"invalid repeat expression: ${min} is larger than ${max}")
          }
        case (Some(min),None) =>
          if (min < 0) {
            throw RegExp.InvalidRegExpException(s"invalid repeat expression: min and max must be positive")
          }
        case (None,Some(max)) =>
          if (max < 0) {
            throw RegExp.InvalidRegExpException(s"invalid repeat expression: min and max must be positive")
          }
        case (None,None) =>
          throw RegExp.InvalidRegExpException("invalid repeat expression: either min or max must be specified")
      }
    }

    validate()

    (min,max) match {
      case (min,Some(0)) => EpsExp()
      case (Some(0),None) => StarExp(r,greedy)
      case (Some(0),max) => new RepeatExp(r,None,max,greedy)
      case _ => new RepeatExp(r,min,max,greedy)
    }
  }
}
case class GroupExp[A](r: RegExp[A], id: Int, name: Option[String]) extends RegExp[A]
case class BackReferenceExp[A](n: Int, name: Option[String]) extends RegExp[A]
case class StartAnchorExp[A]() extends RegExp[A]
case class EndAnchorExp[A]() extends RegExp[A]
case class LookaheadExp[A](r: RegExp[A], positive: Boolean) extends RegExp[A]
case class LookbehindExp[A](r: RegExp[A], positive: Boolean) extends RegExp[A]
case class IfExp[A](cond: RegExp[A], rt: RegExp[A], rf: RegExp[A]) extends RegExp[A]
case class DotExp() extends RegExp[Char]
case class CharClassExp(es: Seq[CharClassElem], positive: Boolean) extends RegExp[Char]
case class MetaCharExp(c: Char) extends RegExp[Char] with CharClassElem {
  val negetiveChar = Set('D', 'H', 'S', 'V', 'W')

  val charSet = c match {
    case 'd' | 'D' => ('0' to '9').toSet
    case 'h' | 'H' => Set('\u0009')
    case 'R' => Set('\r', '\n')
    case 's' | 'S' => Set(' ', '\t', '\n', '\r', '\f')
    case 'v' | 'V' => Set('\u000B')
    case 'w' | 'W' => ('a' to 'z').toSet | ('A' to 'Z').toSet | ('0' to '9').toSet + '_'

    case _ => throw RegExp.InvalidRegExpException(s"invalid meta character: \\${c}")
  }

  val negative = negetiveChar(c)
}


object RegExp {
  case class InvalidRegExpException(message: String) extends Exception(message: String)

  class PCREOption(s: String = "") {
    var ignoreCase = false
    var dotAll = false
    var ungreedy = false

    s.foreach{
      case 'i' => ignoreCase = true
      case 's' => dotAll = true
      case 'U' => ungreedy = true
      case c => throw RegExp.InvalidRegExpException(s"unsupported PCRE option: ${c}")
    }
  }

  def toString[A](r: RegExp[A]): String = {
    r match {
      case ElemExp(a) => IO.escape(a)
      case EmptyExp() => "∅"
      case EpsExp() => "ε"
      case ConcatExp(r1,r2) => s"(${r1}${r2})"
      case AltExp(r1,r2) => s"(${r1}|${r2})"
      case StarExp(r,greedy) => s"(${r})*${if (greedy) "" else "?"}"
      case PlusExp(r,greedy) => s"(${r})+${if (greedy) "" else "?"}"
      case OptionExp(r,greedy) => s"(${r})?${if (greedy) "" else "?"}"
      case DotExp() => "."
      case RepeatExp(r,min,max,greedy) =>
        if (min == max) {
          s"${r}{${min.get}}${if (greedy) "" else "?"}"
        } else {
          s"${r}{${min.getOrElse("")},${max.getOrElse("")}}${if (greedy) "" else "?"}"
        }
      case CharClassExp(es,positive) => s"[${if (positive) "" else "^"}${es.mkString}]"
      case MetaCharExp(c) => s"\\${c}"
      case GroupExp(r,_,name) => name match {
        case Some(name) => s"(?<${name}>${r})"
        case None => s"(${r})"
      }
      case StartAnchorExp() => "^"
      case EndAnchorExp() => "$"
      case BackReferenceExp(n, name) => name match {
        case Some(name) => s"(?P=${name})"
        case None => s"\\${n}"
      }
      case LookaheadExp(r,positive) => s"(?${if (positive) "=" else "!"}${r})"
      case LookbehindExp(r,positive) => s"(?<${if (positive) "=" else "!"}${r})"
      case IfExp(cond,rt,rf) => s"(?(${cond})${rt}|${rf})"
    }
  }

  def optConcatExp[A](r1: RegExp[A], r2: RegExp[A]): RegExp[A] = {
    (r1,r2) match {
      case (EpsExp(),_) => r2
      case (_,EpsExp()) => r1
      case (_,RepeatExp(`r1`,min,max,greedy)) =>
        RepeatExp(r1,min.orElse(Some(0)).map(_+1),max.map(_+1),greedy)
      case _ => ConcatExp(r1,r2)
    }
  }

  def constructTransducer(
    r: RegExp[Char], option: PCREOption = new PCREOption()
  ): DetTransducer[(RegExp[Char], Boolean), Option[Char]] = {
    def getElems(r: RegExp[Char]): Set[Char] = {
      r match {
        case ElemExp(a) => if (option.ignoreCase && a.isLetter) Set(a.toLower) else Set(a)
        case EmptyExp() | EpsExp() | BackReferenceExp(_,_) | StartAnchorExp() | EndAnchorExp() => Set()
        case ConcatExp(r1,r2) => getElems(r1) | getElems(r2)
        case AltExp(r1,r2) => getElems(r1) | getElems(r2)
        case StarExp(r,greedy) => getElems(r)
        case PlusExp(r,greedy) => getElems(r)
        case OptionExp(r,greedy) => getElems(r)
        case DotExp() => if (option.dotAll) Set() else Set('\n')
        case RepeatExp(r,min,max,greedy) => getElems(r)
        case GroupExp(r,_,_) => getElems(r)
        case LookaheadExp(r,_) => getElems(r)
        case LookbehindExp(r,_) => getElems(r)
        case IfExp(cond,rt,rf) => getElems(cond) | getElems(rt) | getElems(rf)
        case r @ CharClassExp(es,positive) =>
          val s = es.flatMap(_.charSet).toSet
          if (option.ignoreCase) {
            s.map{
              case a if a.isLetter => a.toLower
              case a => a
            }
          } else s
        case r @ MetaCharExp(c) =>
          val s = r.charSet
          if (option.ignoreCase) {
            s.map{
              case a if a.isLetter => a.toLower
              case a => a
            }
          } else s
        case _ => throw new Exception(s"getElems unsupported expression: ${r}")
      }
    }

    def isStartAnchorHead[A](r: RegExp[A]): Boolean = {
      r match {
        case StartAnchorExp() => true
        case ConcatExp(r1,_) => isStartAnchorHead(r1)
        case AltExp(r1,r2) => isStartAnchorHead(r1) && isStartAnchorHead(r2)
        case PlusExp(r,_) => isStartAnchorHead(r)
        case RepeatExp(r,min,_,_) if min.isDefined => isStartAnchorHead(r)
        case GroupExp(r,_,_) => isStartAnchorHead(r)
        case _ => false
      }
    }

    val r0 = if (isStartAnchorHead(r)) r else ConcatExp(StarExp(DotExp(), false), r) // simulates suffix match

    val sigma = getElems(r0).map(Option(_)) + None // None: character which does not appear in given expression
    var states = Set((r0, true))
    val stack = Stack((r0, true))
    var delta = Map[
      ((RegExp[Char], Boolean), Option[Option[Char]]),
      DTree[(RegExp[Char], Boolean), (RegExp[Char], Boolean)]
    ]()
    implicit val deriver = new RegExpDeriver[DTree](option)

    while (stack.nonEmpty) {
      Analysis.checkInterrupted("regular expression -> transducer")
      val s @ (r,b) = stack.pop
      val u = if (b) Nil else List(None)
      sigma.foreach{ a =>
        val t: DTree[(RegExp[Char], Boolean), (RegExp[Char], Boolean)] = r.derive(a,u) >>= {
          case Some(r) => DLeaf((r, false))
          case None => DSuccess() // simulates prefix match
        }
        delta += (s,Some(a)) -> t
        val newExps = DTreeMonad.leaves(t).filterNot(states.contains)
        states |= newExps.toSet
        stack.pushAll(newExps)
      }
      val t: DTree[(RegExp[Char], Boolean), (RegExp[Char], Boolean)] = r.deriveEOL(u) >>= (_ => DSuccess())
      delta += (s,None) -> t
    }

    new DetTransducer(states, sigma, (r0, true), delta)
  }

  def calcTimeComplexity(
    r: RegExp[Char],
    option: PCREOption = new PCREOption(),
    method: Option[BacktrackMethod]
  ): (Option[Int], Witness[Char]) = {
    def convertWitness(w: Witness[Option[Char]]): Witness[Char] = {
      val charForNone = '.'
      Witness(w.separators.map(_.map(_.getOrElse(charForNone))), w.pumps.map(_.map(_.getOrElse(charForNone))))
    }

    val transducer = Debug.time("regular expression -> transducer") {
      constructTransducer(r,option)
    }.rename()

    val (growthRate, witness) = method match {
      case Some(method) => transducer.calcGrowthRateBacktrack(method)
      case None => transducer.calcGrowthRate()
    }
    (growthRate, convertWitness(witness))
  }

  def getTransducerSize(
    r: RegExp[Char],
    option: PCREOption = new PCREOption()
  ): Int = {
    val transducer = constructTransducer(r,option).rename()

    transducer.deltaDet.size
  }
}


sealed trait CharClassElem {
  val charSet: Set[Char]
  override def toString(): String = CharClassElem.toString(this)
}

case class SingleCharExp(c: Char) extends CharClassElem {
  val charSet = Set(c)
}
case class RangeExp(start: Char, end: Char) extends CharClassElem {
  val charSet = (start to end).toSet
}


object CharClassElem {
  def toString(e: CharClassElem): String = {
    e match {
      case SingleCharExp(c) => IO.escape(c)
      case RangeExp(start, end) => s"${IO.escape(start)}-${IO.escape(end)}"
      case MetaCharExp(c) => s"\\${c}"
    }
  }
}
