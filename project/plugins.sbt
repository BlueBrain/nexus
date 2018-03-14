resolvers += Resolver.bintrayRepo("bbp", "nexus-releases")

addSbtPlugin("ch.epfl.bluebrain.nexus" % "sbt-nexus"     % "0.10.4")
addSbtPlugin("com.eed3si9n"            % "sbt-buildinfo" % "0.7.0")
addSbtPlugin("com.lightbend.paradox"   % "sbt-paradox"   % "0.3.2")
