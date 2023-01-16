package matching.regexp

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import RegExp._
import matching.transition._

class RegExpSpec extends AnyFlatSpec with Matchers {
  "optConcatExp" should "concat expressions with optimization on ε" in {
    optConcatExp(ElemExp('a'),ElemExp('b')) should be (ConcatExp(ElemExp('a'),ElemExp('b')))
    optConcatExp(EpsExp(),ElemExp('b')) should be (ElemExp('b'))
    optConcatExp(ElemExp('a'),EpsExp()) should be (ElemExp('a'))
    optConcatExp(EpsExp(),EpsExp()) should be (EpsExp())
  }

  it should "concat expressions with optimization on repeat expression" in {
    optConcatExp(ElemExp('a'),RepeatExp(ElemExp('a'),Some(3),Some(5),true)) should be (
      RepeatExp(ElemExp('a'),Some(4),Some(6),true)
    )
    optConcatExp(ElemExp('a'),RepeatExp(ElemExp('a'),None,Some(5),false)) should be (
      RepeatExp(ElemExp('a'),Some(1),Some(6),false)
    )
    optConcatExp(ElemExp('a'),RepeatExp(ElemExp('a'),Some(3),None,true)) should be (
      RepeatExp(ElemExp('a'),Some(4),None,true)
    )
    optConcatExp(ElemExp('a'),RepeatExp(ElemExp('b'),Some(3),Some(5),true)) should be (
      ConcatExp(ElemExp('a'),RepeatExp(ElemExp('b'),Some(3),Some(5),true))
    )
  }

  "RepeatExp.apply()" should "transform expression properly" in {
    val r = ElemExp('a')
    RepeatExp(r,Some(2),Some(5),true) should be (new RepeatExp(r,Some(2),Some(5),true))
    RepeatExp(r,None,Some(5),true) should be (new RepeatExp(r,None,Some(5),true))
    RepeatExp(r,Some(0),Some(5),true) should be (new RepeatExp(r,None,Some(5),true))
    RepeatExp(r,Some(0),Some(0),true) should be (EpsExp())
    RepeatExp(r,Some(0),None,true) should be (StarExp(r,true))

    a [InvalidRegExpException] should be thrownBy {RepeatExp(r,Some(5),Some(2),true)}
    a [InvalidRegExpException] should be thrownBy {RepeatExp(r,None,None,true)}
    a [InvalidRegExpException] should be thrownBy {RepeatExp(r,Some(-1),None,true)}
    a [InvalidRegExpException] should be thrownBy {RepeatExp(r,None,Some(-1),true)}
  }

  "constructTransducer" should "construct transducer which simulates exhaustive search" in {
    val r0 = RegExpParser("^(?:a*a*b|ba)")
    val r1 = RegExpParser("a*a*b")
    val r2 = RegExpParser("a*b")
    val r3 = RegExpParser("a")
    val r4 = RegExpParser("ε")
    val transducer = constructTransducer(r0)
    transducer.states should be (Set((r0,true), (r1,false), (r2,false), (r3,false), (r4,false)))
    transducer.sigma should be (Set(Some('a'),Some('b'),None))
    transducer.initialState should be ((r0,true))
    transducer.delta should have size (20)
  }

  "modifyRegExp" should "convert/approximate the given expression to an analyzable one" in {
    modifyRegExp(RegExpParser("ab|cd*"))._1 should be (RegExpParser(".*?(?:ab|cd*)"))
    modifyRegExp(RegExpParser("^ab*"))._1 should be (RegExpParser("^ab*"))
    modifyRegExp(RegExpParser("^a(?<=b)"))._1 should be (
      List[RegExp[Char]](
        StartAnchorExp(),
        ElemExp('a'),
        FailEpsExp()
      ).reduceLeft(ConcatExp(_,_)))
    modifyRegExp(RegExpParser("^a(?<!ab|c)"))._1 should be (
      List[RegExp[Char]](
        StartAnchorExp(),
        ElemExp('a'),
        FailEpsExp()
      ).reduceLeft(ConcatExp(_,_)))
    modifyRegExp(RegExpParser("""^a\b"""))._1 should be (
      List[RegExp[Char]](
        StartAnchorExp(),
        ElemExp('a'),
        FailEpsExp()
      ).reduceLeft(ConcatExp(_,_)))
    modifyRegExp(RegExpParser("""^(a)\1"""))._1 should be (
      List[RegExp[Char]](
        StartAnchorExp(),
        ElemExp('a'),
        ConcatExp(ElemExp('a'), FailEpsExp())
      ).reduceLeft(ConcatExp(_,_)))
    modifyRegExp(RegExpParser("""^(a)\1(?<hoge>b*)(?P=hoge)"""))._1 should be (
      List[RegExp[Char]](
        StartAnchorExp(),
        ElemExp('a'),
        ConcatExp(ElemExp('a'), FailEpsExp()),
        StarExp(ElemExp('b'),true),
        ConcatExp(StarExp(ElemExp('b'),true), FailEpsExp()),
      ).reduceLeft(ConcatExp(_,_)))
    modifyRegExp(RegExpParser("""^((?<=a)b)\1"""))._1 should be (
      List[RegExp[Char]](
        StartAnchorExp(),
        ConcatExp(FailEpsExp(), ElemExp('b')),
        ConcatExp(ElemExp('b'), FailEpsExp())
      ).reduceLeft(ConcatExp(_,_)))
    modifyRegExp(RegExpParser("""^(a)(b\1)\2"""))._1 should be (
      List[RegExp[Char]](
        StartAnchorExp(),
        ElemExp('a'),
        ConcatExp(ElemExp('b'), ConcatExp(ElemExp('a'), FailEpsExp())),
        ConcatExp(ConcatExp(ElemExp('b'), ConcatExp(ElemExp('a'), FailEpsExp())), FailEpsExp())
      ).reduceLeft(ConcatExp(_,_)))
  }

  it should "throw exception if the given expression has unsupported features" in {
    a [InvalidRegExpException] should be thrownBy {modifyRegExp(RegExpParser("(?(a)b|c)"))}
    a [InvalidRegExpException] should be thrownBy {modifyRegExp(RegExpParser("(?=(?<=a))"))}
    a [InvalidRegExpException] should be thrownBy {modifyRegExp(RegExpParser("(?!(?<!a))"))}
    a [InvalidRegExpException] should be thrownBy {modifyRegExp(RegExpParser("""(?=\b)"""))}
    a [InvalidRegExpException] should be thrownBy {modifyRegExp(RegExpParser("(?<=a*)"))}
    a [InvalidRegExpException] should be thrownBy {modifyRegExp(RegExpParser("(?<!a+)"))}
    a [InvalidRegExpException] should be thrownBy {modifyRegExp(RegExpParser("""(?=(a))\1"""))}
    a [InvalidRegExpException] should be thrownBy {modifyRegExp(RegExpParser("""(a)(?=\1)"""))}
    a [InvalidRegExpException] should be thrownBy {modifyRegExp(RegExpParser("""(a|\1)"""))}
    a [InvalidRegExpException] should be thrownBy {modifyRegExp(RegExpParser("""(a\2)(\1|b\1)"""))}
    a [InvalidRegExpException] should be thrownBy {modifyRegExp(RegExpParser("""(a\2)(\3|b)|(\1c*)"""))}
  }

  "calcTimeComplexity" should "calculate time complexity of backtrack matching" in {
    val option = new PCREOptions()
    val method = Some(Lookahead)
    option.dotAll = true

    calcTimeComplexity(RegExpParser("^abc$"),
      option, method)._1 should be (Some(0))

    calcTimeComplexity(RegExpParser("^a*$"),
      option, method)._1 should be (Some(1))

    calcTimeComplexity(RegExpParser("^a*a*$"),
      option, method)._1 should be (Some(2))

    calcTimeComplexity(RegExpParser("^((ab)*){3}$"),
      option, method)._1 should be (Some(3))

    calcTimeComplexity(RegExpParser("^a*a*bcd*d*$"),
      option, method)._1 should be (Some(3))

    calcTimeComplexity(RegExpParser("^(a|a)*$"),
      option, method)._1 should be (None)

    calcTimeComplexity(RegExpParser("^(a*)*$"),
      option, method)._1 should be (None)

    calcTimeComplexity(RegExpParser("^.*a.*a.*$"),
      option, method)._1 should be (Some(1))

    calcTimeComplexity(RegExpParser("^.*a.*b.*$"),
      option, method)._1 should be (Some(2))

    calcTimeComplexity(RegExpParser("^(?=a*$)a*a*$"),
      option, method)._1 should be (Some(1))
  }
}
