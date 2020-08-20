addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.9")

addSbtPlugin("org.scalameta"          % "sbt-scalafmt"  % "2.2.1")
addSbtPlugin("org.scoverage"          % "sbt-scoverage" % "1.6.1")
addSbtPlugin("com.sksamuel.scapegoat" % "sbt-scapegoat" % "1.1.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.7.3")

addSbtPlugin("com.typesafe.sbt"      % "sbt-ghpages"                % "0.6.3")
addSbtPlugin("com.typesafe.sbt"      % "sbt-site"                   % "1.3.3") // cannot upgrade to 1.4.0 because of paradox material theme
addSbtPlugin("com.lightbend.paradox" % "sbt-paradox"                % "0.6.7")
addSbtPlugin("io.github.jonas"       % "sbt-paradox-material-theme" % "0.5.1")

addSbtPlugin("ch.epfl.scala" % "sbt-release-early" % "2.1.1")

addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.3.7")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.10")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.9.0")
