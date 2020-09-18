package ch.epfl.bluebrain.nexus.delta.service.plugin

import java.net.URL

import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader
//TODO make this parent last class loader
class PluginClassLoader(url: URL, parent: ClassLoader) extends URLClassLoader(Seq(url), parent)
