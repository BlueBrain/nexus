resolvers += Resolver.bintrayRepo("bbp", "nexus-releases")
addSbtPlugin("ch.epfl.bluebrain.nexus" % "sbt-nexus" % "0.14.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages"                % "0.6.2")
addSbtPlugin("io.github.jonas"  % "sbt-paradox-material-theme" % "0.5.1")
addSbtPlugin("com.typesafe.sbt" % "sbt-site"                   % "1.3.2")
addSbtPlugin("com.lightbend.paradox" % "sbt-paradox" % "0.4.3")
