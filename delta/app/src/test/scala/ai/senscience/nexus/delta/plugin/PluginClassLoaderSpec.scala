package ai.senscience.nexus.delta.plugin

import ai.senscience.nexus.delta.testplugin.ClassLoaderTestClassImpl
import ch.epfl.bluebrain.nexus.testkit.plugin.ClassLoaderTestClass
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec
import org.scalatest.BeforeAndAfterAll

import java.nio.file.Path
import scala.io.Source

class PluginClassLoaderSpec extends BaseSpec with BeforeAndAfterAll {

  val jarPath = Path.of("../plugins/test-plugin/target/delta-test-plugin.jar")
  val cl      = new PluginClassLoader(
    jarPath.toUri.toURL,
    this.getClass.getClassLoader
  )

  override def beforeAll(): Unit = {
    // make sure that the jar file exists in expected place
    val _ = jarPath.toFile.exists() shouldEqual true
  }

  "A PluginClassLoader" should {

    "load class from plugin classpath first" in {

      cl.create[ClassLoaderTestClass](
        classOf[ClassLoaderTestClassImpl].getName,
        println
      )()
        .loadedFrom shouldEqual "plugin classpath"
    }
    "load resource from plugin classpath first" in {
      Source
        .fromInputStream(cl.getResourceAsStream("plugin-classloader-test.txt"))
        .mkString shouldEqual "plugin classpath"

    }
    "load resource from application classpath if not found in plugin classpath" in {
      Source
        .fromInputStream(cl.getResourceAsStream("plugin-classloader-test-from-application.txt"))
        .mkString shouldEqual "application classpath"
    }
  }

}
