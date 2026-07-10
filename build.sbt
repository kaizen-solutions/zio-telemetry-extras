import org.typelevel.scalacoptions.ScalacOptions

inThisBuild {
  val scala213 = "2.13.18"
  val scala3   = "3.3.8"

  Seq(
    scalaVersion                     := scala213,
    crossScalaVersions               := Seq(scala213, scala3),
    semanticdbEnabled                := true,
    semanticdbVersion                := scalafixSemanticdb.revision,
    Test / fork                      := true,
    Test / parallelExecution         := true,
    Test / testForkedParallel        := true,
    versionScheme                    := Some("early-semver"),
    githubWorkflowJavaVersions       := List(JavaSpec.temurin("21"), JavaSpec.temurin("25")),
    githubWorkflowPublishJavaVersion := JavaSpec.temurin("21"),
    githubWorkflowTargetTags ++= Seq("v*"),
    githubWorkflowPublishTargetBranches := Seq(
      RefPredicate.StartsWith(Ref.Tag("v")),
      RefPredicate.Equals(Ref.Branch("main"))
    ),
    githubWorkflowPublish := Seq(
      WorkflowStep.Sbt(
        commands = List("ci-release"),
        name = Some("Publish project"),
        env = Map(
          "PGP_PASSPHRASE"    -> "${{ secrets.PGP_PASSPHRASE }}",
          "PGP_SECRET"        -> "${{ secrets.PGP_SECRET }}",
          "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
          "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
        )
      )
    ),
    developers := List(
      Developer(
        "calvinlfer",
        "Calvin Fernandes",
        "cal@kaizen-solutions.io",
        url("https://www.kaizen-solutions.io")
      )
    ),
    licenses         := List("MIT" -> url("https://opensource.org/licenses/MIT")),
    organization     := "io.kaizen-solutions",
    organizationName := "kaizen-solutions",
    homepage         := Option(url("https://www.kaizen-solutions.io"))
  )
}

// Keep outside inThisBuild: +compile changes each project / scalaVersion, while ThisBuild / scalaVersion
// stays at default. This avoids passing Scala 2-only -Xsource:3 to Scala 3.
ThisBuild / scalacOptions ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 13))  => Seq(ScalacOptions.source3.option)
    case Some(_) | None => Seq.empty
  }
}

lazy val isScala3 = Def.setting(
  CrossVersion
    .partialVersion(scalaVersion.value)
    .exists { case (major, _) => major == 3 }
)

lazy val kindProjectorSettings = Seq(
  libraryDependencies ++= {
    if (isScala3.value) Nil
    else
      Seq(compilerPlugin(("org.typelevel" %% "kind-projector" % "0.13.4").cross(CrossVersion.full)))
  }
)

lazy val http4s = project
  .in(file("http4s"))
  .settings(kindProjectorSettings *)
  .settings(Test / tpolecatExcludeOptions := Set(ScalacOptions.lintInferAny))
  .settings(
    name := "zio-telemetry-extras-http4s",
    libraryDependencies ++= Dependencies.http4s ++ Dependencies.zio ++ Dependencies.openTelemetry
  )

lazy val `smithy4s-series-18` =
  project
    .in(file("smithy4s-series-18"))
    .dependsOn(http4s % "compile->compile;test->test")
    .settings(kindProjectorSettings *)
    .settings(Test / tpolecatExcludeOptions := Set(ScalacOptions.lintInferAny))
    .settings(
      name := "zio-telemetry-extras-smithy4s-series-18",
      libraryDependencies ++= Dependencies.smithy4s.`0.18.x`
    )

lazy val `smithy4s-series-19` =
  project
    .in(file("smithy4s-series-19"))
    .dependsOn(http4s % "compile->compile;test->test")
    .settings(kindProjectorSettings *)
    .settings(Test / tpolecatExcludeOptions := Set(ScalacOptions.lintInferAny))
    .settings(
      name := "zio-telemetry-extras-smithy4s-series-19",
      libraryDependencies ++= Dependencies.smithy4s.`0.19.x`
    )

lazy val root = project
  .in(file("."))
  .settings(publish / skip := true)
  .aggregate(http4s, `smithy4s-series-18`, `smithy4s-series-19`)

addCommandAlias("lint", "; scalafmtAll; scalafixAll")
addCommandAlias("lintEnforce", "; scalafmtCheckAll; scalafixAll --check")
