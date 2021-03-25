package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.Interval
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewFields
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjectionFields.{ElasticSearchProjectionFields, SparqlProjectionFields}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.UnexpectedCompositeViewId
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSourceFields._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.serialization.CompositeViewFieldsJsonLdSourceDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Group
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, NonEmptySet}
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import io.circe.literal.JsonStringContext
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.concurrent.duration.DurationInt

class CompositeViewDecodingSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with IOValues
    with TestHelpers
    with RemoteContextResolutionFixture {

  private val project = ProjectGen.project("org", "project")

  val uuid                          = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val decoder = CompositeViewFieldsJsonLdSourceDecoder(uuidF)

  val query1 =
    "prefix music: <http://music.com/> prefix nxv: <https://bluebrain.github.io/nexus/vocabulary/> CONSTRUCT {{resource_id}   music:name       ?bandName ; music:genre      ?bandGenre ; music:album      ?albumId . ?albumId        music:released   ?albumReleaseDate ; music:song       ?songId . ?songId         music:title      ?songTitle ; music:number     ?songNumber ; music:length     ?songLength } WHERE {{resource_id}   music:name       ?bandName ; music:genre      ?bandGenre . OPTIONAL {{resource_id} ^music:by        ?albumId . ?albumId        music:released   ?albumReleaseDate . OPTIONAL {?albumId         ^music:on        ?songId . ?songId          music:title      ?songTitle ; music:number     ?songNumber ; music:length     ?songLength } } } ORDER BY(?songNumber)"
  val query2 =
    "prefix music: <http://music.com/> prefix nxv: <https://bluebrain.github.io/nexus/vocabulary/> CONSTRUCT {{resource_id}             music:name               ?albumTitle ; music:length             ?albumLength ; music:numberOfSongs      ?numberOfSongs } WHERE {SELECT ?albumReleaseDate ?albumTitle (sum(?songLength) as ?albumLength) (count(?albumReleaseDate) as ?numberOfSongs) WHERE {OPTIONAL { {resource_id}           ^music:on / music:length   ?songLength } {resource_id} music:released             ?albumReleaseDate ; music:title                ?albumTitle . } GROUP BY ?albumReleaseDate ?albumTitle }"

  val mapping =
    json"""{
        "properties": {
          "@type": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "@id": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "name": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "genre": {
            "type": "keyword",
            "copy_to": "_all_fields"
          },
          "_all_fields": {
            "type": "text"
          }
        },
        "dynamic": false
      }
      """
  val context =
    json"""
      {
        "@base": "http://music.com/",
        "@vocab": "http://music.com/"
      }"""

  val compositeViewValue      = CompositeViewFields(
    NonEmptySet.of(
      ProjectSourceFields(
        Some(iri"http://music.com/sources/local")
      ),
      CrossProjectSourceFields(
        Some(iri"http://music.com/sources/albums"),
        ProjectGen.project("demo", "albums").ref,
        Set(Group("mygroup", Label.unsafe("myrealm")))
      ),
      RemoteProjectSourceFields(
        Some(iri"http://music.com/sources/songs"),
        ProjectGen.project("remote_demo", "songs").ref,
        Uri("https://example2.nexus.com"),
        Some(Secret("mytoken"))
      )
    ),
    NonEmptySet.of(
      ElasticSearchProjectionFields(
        Some(iri"http://music.com/bands"),
        query1,
        mapping,
        context,
        resourceTypes = Set(iri"http://music.com/Band")
      ),
      SparqlProjectionFields(
        Some(iri"http://music.com/albums"),
        query2,
        resourceTypes = Set(iri"http://music.com/Album")
      )
    ),
    Interval(10.minutes)
  )
  val compositeViewValueNoIds = CompositeViewFields(
    NonEmptySet.of(
      ProjectSourceFields(
        None
      ),
      CrossProjectSourceFields(
        None,
        ProjectGen.project("demo", "albums").ref,
        Set(Group("mygroup", Label.unsafe("myrealm")))
      ),
      RemoteProjectSourceFields(
        None,
        ProjectGen.project("remote_demo", "songs").ref,
        Uri("https://example2.nexus.com"),
        Some(Secret("mytoken"))
      )
    ),
    NonEmptySet.of(
      ElasticSearchProjectionFields(
        None,
        query1,
        mapping,
        context,
        resourceTypes = Set(iri"http://music.com/Band")
      ),
      SparqlProjectionFields(
        None,
        query2,
        resourceTypes = Set(iri"http://music.com/Album")
      )
    ),
    Interval(10.minutes)
  )

  val source      = jsonContentOf("composite-view.json")
  val sourceNoIds = jsonContentOf("composite-view-no-ids.json")
  "A composite view" should {

    "be decoded correctly from json-ld" when {

      "projections and sources have ids" in {
        val decoded = decoder(
          project,
          source
        ).accepted
        decoded._1 shouldEqual iri"http://music.com/composite/view"
        decoded._2 shouldEqual compositeViewValue
      }

      "projections and sources have ids and composite view id is specified" in {
        decoder(
          project,
          iri"http://music.com/composite/view",
          source
        ).accepted shouldEqual compositeViewValue
      }

      "projections and sources don't have ids" in {
        val decoded = decoder(
          project,
          sourceNoIds
        ).accepted
        decoded._1 shouldEqual iri"http://music.com/composite/view"
        decoded._2 shouldEqual compositeViewValueNoIds
      }

      "projections and sources don't have ids and composite view id is specified" in {
        decoder(
          project,
          iri"http://music.com/composite/view",
          sourceNoIds
        ).accepted shouldEqual compositeViewValueNoIds
      }
    }

    "fail decoding from json-ld" when {
      "the provided id did not match the expected one" in {
        decoder(
          project,
          iri"http://example.com/wrong.id",
          source
        ).rejectedWith[UnexpectedCompositeViewId]
      }
    }
  }
}
