package ch.epfl.bluebrain.nexus.delta.testplugin

import ch.epfl.bluebrain.nexus.testkit.plugin.ClassLoaderTestClass

class ClassLoaderTestClassImpl extends ClassLoaderTestClass {

  def loadedFrom: String = "plugin classpath"
}
