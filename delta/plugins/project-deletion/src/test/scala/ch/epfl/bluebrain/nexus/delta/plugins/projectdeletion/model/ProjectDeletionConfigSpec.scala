package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import pureconfig.ConfigSource
import pureconfig.error.ConfigReaderException

import scala.concurrent.duration.DurationInt

class ProjectDeletionConfigSpec extends AnyWordSpecLike with Matchers with IOValues with TestHelpers {

  implicit private val cl: ClassLoader = getClass.getClassLoader
  implicit private val api: JsonLdApi  = JsonLdJavaApi.strict

  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    contexts.projectDeletion -> ContextValue.fromFile("contexts/project-deletion.json").accepted
  )

  "A ProjectDeletionConfig" should {
    val config = ProjectDeletionConfig(
      idleInterval = 10.minutes,
      idleCheckPeriod = 5.seconds,
      deleteDeprecatedProjects = true,
      includedProjects = List("some.+".r),
      excludedProjects = List(".+".r)
    )

    "be encoded correctly to jsonld" in {
      val expected = jsonContentOf("project-deletion-config.json")
      config.toCompactedJsonLd.accepted.json shouldEqual expected
    }

    "be parsed correctly from config" in {
      val configString =
        s"""idle-interval = 10 minutes
           |idle-check-period = 5 seconds
           |delete-deprecated-projects = true
           |included-projects = ["some.+"]
           |excluded-projects = [".+"]
           |""".stripMargin

      val result = ConfigSource.fromConfig(ConfigFactory.parseString(configString)).loadOrThrow[ProjectDeletionConfig]

      // equality for regexes fails
      result.copy(includedProjects = Nil, excludedProjects = Nil) shouldEqual config.copy(
        includedProjects = Nil,
        excludedProjects = Nil
      )
      result.includedProjects.map(_.regex) shouldEqual config.includedProjects.map(_.regex)
      result.excludedProjects.map(_.regex) shouldEqual config.excludedProjects.map(_.regex)
    }

    "fail to be parsed when idle interval is less than the check period" in {
      val configString =
        s"""idle-interval = 5 seconds
           |idle-check-period = 10 minutes
           |delete-deprecated-projects = true
           |included-projects = ["some.+"]
           |excluded-projects = [".+"]
           |""".stripMargin
      intercept[ConfigReaderException[_]] {
        ConfigSource.fromConfig(ConfigFactory.parseString(configString)).loadOrThrow[ProjectDeletionConfig]
      }
    }
  }

}
