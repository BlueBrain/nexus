package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.Interval
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjectionFields.{ElasticSearchProjectionFields, SparqlProjectionFields}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSourceFields.*
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewFields, TemplateSparqlConstructQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.serialization.CompositeViewFieldsJsonLdSourceDecoder
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel.IndexGroup
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.*
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.{DecodingFailed, UnexpectedId}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{IriFilter, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import org.http4s.Uri

import java.util.UUID
import scala.concurrent.duration.DurationInt

class CompositeViewDecodingSpec extends CatsEffectSpec with CirceLiteral with Fixtures {

  private val realm                  = Label.unsafe("myrealm")
  implicit private val alice: Caller = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))

  private val ref = ProjectRef.unsafe("org", "proj")
  private val pc  = ProjectContext.unsafe(
    ApiMappings.empty,
    nxv.base,
    nxv.base,
    enforceSchema = false
  )

  val uuid                          = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  val resolverContext: ResolverContextResolution = ResolverContextResolution(rcr)
  private val decoder                            = CompositeViewFieldsJsonLdSourceDecoder(uuidF, resolverContext, 1.minute)

  val query1 =
    TemplateSparqlConstructQuery(
      "prefix music: <http://music.com/> prefix nxv: <https://bluebrain.github.io/nexus/vocabulary/> CONSTRUCT {{resource_id}   music:name       ?bandName ; music:genre      ?bandGenre ; music:album      ?albumId . ?albumId        music:released   ?albumReleaseDate ; music:song       ?songId . ?songId         music:title      ?songTitle ; music:number     ?songNumber ; music:length     ?songLength } WHERE {{resource_id}   music:name       ?bandName ; music:genre      ?bandGenre . OPTIONAL {{resource_id} ^music:by        ?albumId . ?albumId        music:released   ?albumReleaseDate . OPTIONAL {?albumId         ^music:on        ?songId . ?songId          music:title      ?songTitle ; music:number     ?songNumber ; music:length     ?songLength } } } ORDER BY(?songNumber)"
    ).rightValue
  val query2 =
    TemplateSparqlConstructQuery(
      "prefix music: <http://music.com/> prefix nxv: <https://bluebrain.github.io/nexus/vocabulary/> CONSTRUCT {{resource_id}             music:name               ?albumTitle ; music:length             ?albumLength ; music:numberOfSongs      ?numberOfSongs } WHERE {SELECT ?albumReleaseDate ?albumTitle (sum(?songLength) as ?albumLength) (count(?albumReleaseDate) as ?numberOfSongs) WHERE {OPTIONAL { {resource_id}           ^music:on / music:length   ?songLength } {resource_id} music:released             ?albumReleaseDate ; music:title                ?albumTitle . } GROUP BY ?albumReleaseDate ?albumTitle }"
    ).rightValue

  val mapping =
    jobj"""{
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
    ContextObject(json"""
      {
        "@base": "http://music.com/",
        "@vocab": "http://music.com/"
      }""".asObject.value)

  val compositeViewValue      = CompositeViewFields(
    NonEmptyList.of(
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
        Uri.unsafeFromString("https://example2.nexus.com")
      )
    ),
    NonEmptyList.of(
      ElasticSearchProjectionFields(
        Some(iri"http://music.com/bands"),
        query1,
        Some(IndexGroup.unsafe("cv")),
        mapping,
        context,
        resourceTypes = IriFilter.restrictedTo(iri"http://music.com/Band")
      ),
      SparqlProjectionFields(
        Some(iri"http://music.com/albums"),
        query2,
        resourceTypes = IriFilter.restrictedTo(iri"http://music.com/Album")
      )
    ),
    Some(Interval(1.minutes))
  )
  val compositeViewValueNoIds = CompositeViewFields(
    NonEmptyList.of(
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
        Uri.unsafeFromString("https://example2.nexus.com")
      )
    ),
    NonEmptyList.of(
      ElasticSearchProjectionFields(
        None,
        query1,
        Some(IndexGroup.unsafe("cv")),
        mapping,
        context,
        resourceTypes = IriFilter.restrictedTo(iri"http://music.com/Band")
      ),
      SparqlProjectionFields(
        None,
        query2,
        resourceTypes = IriFilter.restrictedTo(iri"http://music.com/Album")
      )
    ),
    Some(Interval(1.minutes))
  )

  val source      = jsonContentOf("composite-view.json")
  val sourceNoIds = jsonContentOf("composite-view-no-ids.json")
  "A composite view" should {

    "be decoded correctly from json-ld" when {

      "projections and sources have ids" in {
        val decoded = decoder(ref, pc, source).accepted
        decoded._1 shouldEqual iri"http://music.com/composite/view"
        decoded._2 shouldEqual compositeViewValue
      }

      "projections and sources have ids and composite view id is specified" in {
        decoder(
          ref,
          pc,
          iri"http://music.com/composite/view",
          source
        ).accepted shouldEqual compositeViewValue
      }

      "projections and sources don't have ids" in {
        val decoded = decoder(
          ref,
          pc,
          sourceNoIds
        ).accepted
        decoded._1 shouldEqual iri"http://music.com/composite/view"
        decoded._2 shouldEqual compositeViewValueNoIds
      }

      "projections and sources don't have ids and composite view id is specified" in {
        decoder(
          ref,
          pc,
          iri"http://music.com/composite/view",
          sourceNoIds
        ).accepted shouldEqual compositeViewValueNoIds
      }
    }

    "fail decoding from json-ld" when {
      "the provided id did not match the expected one" in {
        decoder(
          ref,
          pc,
          iri"http://example.com/wrong.id",
          source
        ).rejectedWith[UnexpectedId]
      }

      "the resource_id template does not exist" in {
        val r = decoder(
          ref,
          pc,
          source.replaceKeyWithValue("query", "CONSTRUCT {?s ?p ?o} WHERE {?s ?p ?o}")
        ).rejectedWith[DecodingFailed]
        r.reason should endWith("'Required templating '{resource_id}' in the provided SPARQL query is not found'")
      }

      "the query is not a construct query" in {
        val r = decoder(
          ref,
          pc,
          source.replaceKeyWithValue("query", "SELECT {resource_id} WHERE {?s ?p ?o}")
        ).rejectedWith[DecodingFailed]
        r.reason should endWith("'The provided query is not a valid SPARQL query'")
      }

      "the interval is smaller than the configuration minimum interval" in {
        val r = decoder(
          ref,
          pc,
          source.replaceKeyWithValue("value", "30 seconds")
        ).rejectedWith[DecodingFailed]
        r.reason should endWith("'duration must be greater than 1 minute'")
      }
    }
  }
}
