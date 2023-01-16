package matching

import regexp._
import regexp.RegExp._
import tool.Analysis
import tool.Analysis._
import transition._
import tool.{IO, File, Debug}
import io.StdIn
import collection.mutable.{Map => MTMap}
import java.util.Date
import java.text.DateFormat

object Main {
  class Settings() {
    var style: RegExpStyle = Raw
    var method: Option[BacktrackMethod] = Some(Lookahead)
    var timeout: Option[Int] = Some(10)

    override def toString(): String = {
      List(
        s"${"-"*3} settings ${"-"*27}",
        s"style: ${style}",
        s"timeout: ${if (timeout.isDefined) s"${timeout.get}s" else "disabled"}",
        s"${"-"*40}"
      ).mkString("\n")
    }
  }

  def main(args: Array[String]): Unit = {
    def parseArgs(rawArgs: Array[String]): (Option[String], Settings) = {
      def parseOptions(options: List[String], setting: Settings = new Settings()): Settings = {
        options match {
          case "--style" :: style :: options =>
            setting.style = style match {
              case "raw" => Raw
              case "PCRE" => PCRE
              case _ => throw new Exception(s"invalid style option: ${style}")
            }
            parseOptions(options, setting)
          case "--timeout" :: timeout :: options =>
            val t = try {
              timeout.toInt
            } catch {
              case e: NumberFormatException =>
                throw new Exception(s"invalid timeout option: ${timeout}")
            }
            setting.timeout = if (t > 0) Some(t) else None
            parseOptions(options, setting)
          case "--debug" :: options =>
            Debug.debugModeGlobal = true
            parseOptions(options, setting)
          case Nil => setting
          case _ => throw new Exception("invalid option")
        }
      }

      val (args, optionStrs) = rawArgs.toList.span(!_.startsWith("--"))
      val inputFile = args match {
        case arg :: Nil => Some(arg)
        case Nil => None
        case _ => throw new Exception("invalid arguments")
      }

      (inputFile, parseOptions(optionStrs))
    }

    val (inputFile, settings) = parseArgs(args)

    println(settings)

    inputFile match {
      case Some(inputFile) => fileInputTest(inputFile, settings)
      case None => interactiveTest(settings)
    }
  }

  def test(regExpStr: String, settings: Settings): matching.TestResult = {
    try {
      val (r,options) = settings.style match {
        case Raw => (RegExpParser(regExpStr), new PCREOptions())
        case PCRE => RegExpParser.parsePCRE(regExpStr)
      }
      runWithLimit(settings.timeout) {
        calcTimeComplexity(r,options,settings.method)
      } match {
        case (Analysis.Success((growthRate, witness, approximated, ruleSize)),time) =>
          matching.Success(growthRate, witness, approximated, ruleSize, time)
        case (Analysis.Failure(message),_) => matching.Skipped(message)
        case (Analysis.Timeout(_),_) => matching.Timeout
      }
    } catch {
      case e: RegExpParser.ParseException => Error(e.message)
    }
  }

  def interactiveTest(settings: Settings): Unit = {
    var continue = true
    while (continue) {
      println("please input expression. (input blank line to quit)")
      val regExpStr = StdIn.readLine()
      if (regExpStr.isEmpty) {
        continue = false
      } else {
        println(regExpStr)
        val result = test(regExpStr, settings)
        println(result)
        println()
      }
    }
  }

  def fileInputTest(inputFile: String, settings: Settings): Unit = {
    val regExpStrs = IO.loadFile(inputFile).getLines.toSeq
    val total = regExpStrs.length

    val dateFormat = DateFormat.getDateTimeInstance()
    val startTime = dateFormat.format(new Date())

    val dirName = s"output/${inputFile.replaceAll("""\W""","_")}_${startTime.replaceAll("""\W""","-")}"
    IO.createDirectory(dirName)

    val resultFile = IO.createFile(s"${dirName}/result.txt")
    val resultListFile = IO.createFile(s"${dirName}/list.txt")
    val summaryFile = IO.createFile(s"${dirName}/summary.txt")
    val timeFile = IO.createFile(s"${dirName}/time.txt")

    val detailDirNames: Map[(String, Option[Boolean]), String] = (List(
      "constant",
      "linear",
      "polynomial",
      "exponential"
    ).flatMap(resultStr => List(
      (resultStr, Some(false)) -> s"${dirName}/${resultStr}",
      (resultStr, Some(true)) -> s"${dirName}/approximated/${resultStr}"
    )) ++ List(
      "timeout",
      "skipped",
      "error"
    ).flatMap(resultStr =>
      List(
      (resultStr, None) -> s"${dirName}/${resultStr}",
    ))).toMap
    IO.createDirectory(s"${dirName}/approximated")
    detailDirNames.values.foreach(IO.createDirectory)
    val detailFiles = detailDirNames.map{ case (key, name) =>
      key -> IO.createFile(s"${name}/result.txt")
    }.toMap
    val detailListFiles = detailDirNames.map{ case (key, name) =>
      key -> IO.createFile(s"${name}/list.txt")
    }.toMap
    val degreeFiles = MTMap[(Int, Boolean), File]()
    val degreeListFiles = MTMap[(Int, Boolean), File]()

    val summaryCount = MTMap[(String, Option[Boolean]), Int]().withDefaultValue(0)
    val degreeCount = MTMap[(Int, Boolean), Int]().withDefaultValue(0)

    def writeResult(regExpStr: String, result: TestResult): Unit = {
      val resultStr = result match {
        case matching.Success(d,_,_,_,_) => d match {
          case Some(0) => "constant"
          case Some(1) => "linear"
          case Some(d) => "polynomial"
          case None => "exponential"
        }
        case matching.Skipped(_) => "skipped"
        case matching.Error(_) => "error"
        case matching.Timeout => "timeout"
      }

      val approximated = result match {
        case matching.Success(_,_,b,_,_) => Some(b)
        case _ => None
      }

      println(result.toString)
      println()

      resultFile.writeln(regExpStr)
      resultFile.writeln(result.toString)
      resultFile.writeln()
      resultListFile.writeln(regExpStr)

      detailFiles((resultStr,approximated)).writeln(regExpStr)
      detailFiles((resultStr,approximated)).writeln(result.toString)
      detailFiles((resultStr,approximated)).writeln()
      detailListFiles((resultStr,approximated)).writeln(regExpStr)

      result match {
        case s: matching.Success =>
          timeFile.writeln(s.getTime())
        case _ => // NOP
      }

      summaryCount((resultStr,approximated)) += 1

      result match {
        case matching.Success(Some(d),_,b,_,_) if d >= 2 =>
          if (!degreeFiles.contains((d,b))) {
            IO.createDirectory(s"${detailDirNames(("polynomial",Some(b)))}/degree_${d}")
            degreeFiles += (d,b) -> IO.createFile(
              s"${detailDirNames(("polynomial",Some(b)))}/degree_${d}/result.txt")
            degreeListFiles += (d,b) -> IO.createFile(
              s"${detailDirNames(("polynomial",Some(b)))}/degree_${d}/list.txt")
          }

          degreeFiles((d,b)).writeln(regExpStr)
          degreeFiles((d,b)).writeln(result.toString)
          degreeFiles((d,b)).writeln()
          degreeListFiles((d,b)).writeln(regExpStr)

          degreeCount((d,b)) += 1
        case _ => // NOP
      }
    }

    val started = new Date().getTime()

    def printProgress(idx: Int): Unit = {
      var expected: Date = null
      if (idx > 0) {
        val now = new Date().getTime()
        val average = (now - started) / idx
        expected = new Date(now + average * (total - idx))
      }
      println(s"${idx+1}/${total}, estimated to finish at: ${
        if (expected != null) dateFormat.format(expected) else "--------"
      }")
    }

    regExpStrs.zipWithIndex.foreach{ case (regExpStr,idx) =>
      printProgress(idx)
      println(regExpStr)
      writeResult(regExpStr, test(regExpStr, settings))
    }

    val finishTime = dateFormat.format(new Date())

    summaryFile.writeln(s"input file : ${inputFile}")
    summaryFile.writeln(s"started at : ${startTime}")
    summaryFile.writeln(s"finished at: ${finishTime}")
    summaryFile.writeln()

    summaryFile.writeln(settings.toString)
    summaryFile.writeln()

    summaryFile.writeln(s"${"-"*3} result ${"-"*29}")
    summaryFile.writeln(f"${"total"}%-12s: ${total}")

    summaryFile.writeln()
    List("constant", "linear", "polynomial").foreach{ resultStr =>
      summaryFile.writeln(f"${resultStr}%-12s: ${summaryCount((resultStr, Some(false)))}")
    }
    degreeCount.toSeq.collect{
      case (d,count) if !d._2 => (d._1, count)
    }.sortBy(_._1).foreach{ case (degree,count) =>
      summaryFile.writeln(s"degree ${degree}: ${count}", 10)
    }
    summaryFile.writeln(s"exponential : ${summaryCount(("exponential", Some(false)))}")

    val approximatedCount = summaryCount.filterKeys(_._2 == Some(true)).values.sum
    if (approximatedCount > 0) {
      summaryFile.writeln()
      summaryFile.writeln(s"approximated: ${approximatedCount}")
      List("constant", "linear", "polynomial").foreach{ resultStr =>
        summaryFile.writeln(f"${resultStr}%-11s: ${summaryCount((resultStr, Some(true)))}", 1)
      }
      degreeCount.toSeq.collect{
        case (d,count) if d._2 => (d._1, count)
      }.sortBy(_._1).foreach{ case (degree,count) =>
        summaryFile.writeln(s"degree ${degree}: ${count}", 10)
      }
      summaryFile.writeln(s"exponential: ${summaryCount(("exponential", Some(true)))}", 1)
      summaryFile.writeln()
    }

    List("timeout", "skipped", "error").foreach{ resultStr =>
      summaryFile.writeln(f"${resultStr}%-12s: ${summaryCount((resultStr, None))}")
    }
    summaryFile.writeln(s"${"-"*40}")

    resultFile.close()
    resultListFile.close()
    timeFile.close()
    summaryFile.close()
    detailFiles.values.foreach(_.close())
    detailListFiles.values.foreach(_.close())
    degreeFiles.values.foreach(_.close())
    degreeListFiles.values.foreach(_.close())
  }
}
