package ch.epfl.bluebrain.nexus.tests.plugins.blazegraph

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.{testRealm, UserCredentials}
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Organizations
import ch.epfl.bluebrain.nexus.tests.plugins.blazegraph.IncomingOutgoingBlazegraphSpec.Reference
import io.circe.generic.semiauto.deriveDecoder
import io.circe.optics.JsonPath.root
import io.circe.{Decoder, Json}

import java.time.Instant

/* Tests that /incoming and /outgoing sub-resources for a resource returns correct responses.
 *
 * Steps:
 * 1. create vanilla organization and project
 * 2. creates 2 linked resources
 * 3. waits for the project to be indexed into a Blazegraph namespace
 * 4. test the incoming references
 * 5. test the outgoing references
 */
class IncomingOutgoingBlazegraphSpec extends BaseSpec with EitherValuable {

  private val Radar     = UserCredentials(genString(), genString(), testRealm)
  private val orgLabel  = genId()
  private val projLabel = genId()

  "BlazegraphPlugin" should {

    "setup a project" in {
      for {
        _ <- aclDsl.addPermission("/", Radar, Organizations.Create)
        _ <- adminDsl.createOrganization(orgLabel, orgLabel, Radar)
        _ <- adminDsl.createProject(orgLabel, projLabel, Json.obj(), Radar)
      } yield succeed
    }

    "create resources" in {
      // create 2 linked resources (Radar ----knows----> Hawkeye)
      val context     =
        json"""{
             "@base": "${config.deltaUri}/resources/$orgLabel/$projLabel/_/",
             "@vocab": "https://schema.org/",
             "knows": {
               "@type": "@id"
             },
             "actor": {
               "@type": "@id"
             }
           }"""
      val radarJson   =
        json"""{
           "@context": $context,
           "@id": "radar",
           "@type": "Person",
           "name": "Walter Eugene O'Reilly",
           "actor": {
             "@id": "mash",
             "@type": "Movie"
            }
        }"""
      val hawkeyeJson =
        json"""{
           "@context": $context,
           "@id": "hawkeye",
           "@type": "Person",
           "name": "Benjamin Franklin Pierce",
           "knows": "radar",
           "actor": {
             "@id": "mash",
             "@type": "Movie"
           }
        }"""
      for {
        _ <- deltaClient.post[Json](s"/resources/$orgLabel/$projLabel", radarJson, Radar) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
        _ <- deltaClient.post[Json](s"/resources/$orgLabel/$projLabel", hawkeyeJson, Radar) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
      } yield succeed
    }

    "wait until resources are indexed to BlazeGraph" in {
      eventually {
        deltaClient.get[Json](s"/views/$orgLabel/$projLabel/graph/statistics", Radar) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          root.processedEvents.long.getOption(json).value shouldEqual 8L
        }
      }
    }

    "return incoming references" in {
      deltaClient.get[Json](s"/resources/$orgLabel/$projLabel/_/radar/incoming", Radar) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        root._total.long.getOption(json).value shouldEqual 2L
        val refs    = root._results.json.getOption(json).value.as[List[Reference]].rightValue
        val resBase = s"${config.deltaUri}/resources/$orgLabel/$projLabel/_"
        refs.map(_.`@id`) shouldEqual List(s"$resBase/radar", s"$resBase/hawkeye")
      }
    }

    "return outgoing references" in {
      deltaClient.get[Json](s"/resources/$orgLabel/$projLabel/_/hawkeye/outgoing", Radar) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        root._total.long.getOption(json).value shouldEqual 2L
        val refs    = root._results.json.getOption(json).value.as[List[Reference]].rightValue
        val resBase = s"${config.deltaUri}/resources/$orgLabel/$projLabel/_"
        refs.map(_.`@id`) shouldEqual List(s"$resBase/radar", s"$resBase/mash")
      }
    }
  }
}

object IncomingOutgoingBlazegraphSpec {

  sealed trait Reference extends Product with Serializable {
    def `@id`: String
  }
  object Reference {
    case class Internal(
        `@id`: String,
        `@type`: String,
        paths: List[String],
        _constrainedBy: String,
        _createdAt: Instant,
        _createdBy: String,
        _deprecated: Boolean,
        _incoming: String,
        _outgoing: String,
        _project: String,
        _rev: Long,
        _schemaProject: String,
        _self: String,
        _updatedAt: Instant,
        _updatedBy: String
    ) extends Reference
    object Internal {
      implicit val internalReferenceDecoder: Decoder[Internal] = deriveDecoder[Internal]
    }

    case class External(
        `@id`: String,
        `@type`: String,
        paths: List[String]
    ) extends Reference
    object External {
      implicit val externalReferenceDecoder: Decoder[External] = deriveDecoder[External]
    }

    implicit val referenceDecoder: Decoder[Reference] =
      Internal.internalReferenceDecoder or External.externalReferenceDecoder.map(_.asInstanceOf[Reference])
  }

}
