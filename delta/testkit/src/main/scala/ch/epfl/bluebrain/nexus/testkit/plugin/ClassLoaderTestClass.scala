package ch.epfl.bluebrain.nexus.testkit.plugin

/**
  * Trait used for testing [[PluginClassLoader]]
  */
trait ClassLoaderTestClass {

  def loadedFrom: String

}
