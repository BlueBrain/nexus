package ch.epfl.bluebrain.nexus.delta.service.plugin

import java.nio.file.Path

import ch.epfl.bluebrain.nexus.delta.testplugin.ClassLoaderTestClassImpl
import ch.epfl.bluebrain.nexus.testkit.ShouldMatchers.convertToAnyShouldWrapper
import ch.epfl.bluebrain.nexus.testkit.plugin.ClassLoaderTestClass
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.io.Source

class PluginClassLoaderSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  val jarPath = Path.of("../plugins/test-plugin/target/delta-test-plugin.jar")
  val cl      = new PluginClassLoader(
    jarPath.toUri.toURL,
    this.getClass.getClassLoader
  )

  override def beforeAll(): Unit = {
    //make sure that the jar file exists in expected place
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
