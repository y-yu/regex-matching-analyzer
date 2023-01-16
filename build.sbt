name := "regex-matching-analyzer"

val scala2Version = "2.13.10"
val scala3Version = "3.2.1"

scalaVersion := scala2Version
crossScalaVersions := Seq(
  scala2Version,
  scala3Version
)

Compile / scalaSource := baseDirectory.value / "src"
Test / scalaSource := baseDirectory.value / "test"

scalacOptions ++= {
  if ((ThisBuild / scalaVersion).value.startsWith("2"))
    Seq("-deprecation", "-feature", "-unchecked", "-Xlint", "-language:higherKinds")
  else
    Nil
}

libraryDependencies += "org.scala-lang.modules" %% "scala-parser-combinators" % "2.1.1"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.15" % "test"

ThisBuild / useSuperShell := false
shellPrompt := {_ => s"${scala.Console.MAGENTA}sbt:${name.value}> ${scala.Console.RESET}"}
run / fork := true
run / connectInput:= true
Global / cancelable := true
