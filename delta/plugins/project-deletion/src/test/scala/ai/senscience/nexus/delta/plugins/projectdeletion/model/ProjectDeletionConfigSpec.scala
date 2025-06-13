package ai.senscience.nexus.delta.plugins.projectdeletion.model

import ai.senscience.nexus.delta.projectdeletion.model.ProjectDeletionConfig
import ai.senscience.nexus.delta.projectdeletion.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, TitaniumJsonLdApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import com.typesafe.config.ConfigFactory
import pureconfig.ConfigSource
import pureconfig.error.ConfigReaderException

import scala.concurrent.duration.DurationInt

class ProjectDeletionConfigSpec extends CatsEffectSpec {

  implicit private val api: JsonLdApi = TitaniumJsonLdApi.strict

  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.fixedIO(
    contexts.projectDeletion -> ContextValue.fromFile("contexts/project-deletion.json")
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
      intercept[ConfigReaderException[?]] {
        ConfigSource.fromConfig(ConfigFactory.parseString(configString)).loadOrThrow[ProjectDeletionConfig]
      }
    }
  }

}
