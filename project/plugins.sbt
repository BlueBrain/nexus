addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.13")

addSbtPlugin("org.scalameta"          % "sbt-scalafmt"              % "2.2.1")
addSbtPlugin("org.scoverage"          % "sbt-scoverage"             % "1.6.1")
addSbtPlugin("com.sksamuel.scapegoat" % "sbt-scapegoat"             % "1.1.0")
addSbtPlugin("com.github.cb372"       % "sbt-explicit-dependencies" % "0.2.15")

addSbtPlugin("com.typesafe.sbt"  % "sbt-native-packager" % "1.7.6")
addSbtPlugin("com.lightbend.sbt" % "sbt-javaagent"       % "0.1.6")
addSbtPlugin("com.eed3si9n"      % "sbt-assembly"        % "0.15.0")
addSbtPlugin("com.eed3si9n"      % "sbt-buildinfo"       % "0.10.0")

addSbtPlugin("com.typesafe.sbt"      % "sbt-ghpages"                % "0.6.3")
addSbtPlugin("com.typesafe.sbt"      % "sbt-site"                   % "1.3.3") // cannot upgrade to 1.4.0 because of paradox material theme
addSbtPlugin("com.lightbend.paradox" % "sbt-paradox"                % "0.6.7")
addSbtPlugin("io.github.jonas"       % "sbt-paradox-material-theme" % "0.5.1")

addSbtPlugin("ch.epfl.scala" % "sbt-release-early" % "2.1.1")

addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.0")

addSbtPlugin("com.tapad" % "sbt-docker-compose" % "1.0.35")
