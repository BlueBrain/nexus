addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.3.1")

addSbtPlugin("org.scalameta"          % "sbt-scalafmt"              % "2.4.6")
addSbtPlugin("org.scoverage"          % "sbt-scoverage"             % "1.9.3")
addSbtPlugin("com.sksamuel.scapegoat" % "sbt-scapegoat"             % "1.1.1")
addSbtPlugin("com.github.cb372"       % "sbt-explicit-dependencies" % "0.2.16")
addSbtPlugin("com.timushev.sbt"       % "sbt-updates"               % "0.6.1")

addSbtPlugin("com.typesafe.sbt"  % "sbt-native-packager" % "1.8.1")
addSbtPlugin("com.lightbend.sbt" % "sbt-javaagent"       % "0.1.6")
addSbtPlugin("com.eed3si9n"      % "sbt-assembly"        % "1.2.0")
addSbtPlugin("com.eed3si9n"      % "sbt-buildinfo"       % "0.11.0")

addSbtPlugin("com.typesafe.sbt"      % "sbt-site"                   % "1.4.1")
addSbtPlugin("com.lightbend.paradox" % "sbt-paradox"                % "0.9.2")
addSbtPlugin("io.github.jonas"       % "sbt-paradox-material-theme" % "0.5.1")

addSbtPlugin("com.dwijnand"   % "sbt-dynver"          % "4.1.1")
addSbtPlugin("com.codecommit" % "sbt-github-packages" % "0.5.2")
addSbtPlugin("com.github.sbt" % "sbt-ci-release"      % "1.5.10")

addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.0")

addDependencyTreePlugin
