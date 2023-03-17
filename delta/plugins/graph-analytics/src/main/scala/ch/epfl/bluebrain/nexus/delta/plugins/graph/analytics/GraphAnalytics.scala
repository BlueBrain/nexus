package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import akka.http.scaladsl.model.Uri.Query
import cats.data.NonEmptySeq
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel, QueryBuilder}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.WrappedElasticSearchClientError
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.config.GraphAnalyticsConfig.TermAggregationsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.{propertiesAggQuery, relationshipsAggQuery, scriptContent, updateRelationshipsScriptId}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.GraphAnalyticsRejection.{InvalidPropertyType, WrappedElasticSearchRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.PropertiesStatistics.propertiesDecoderFromEsAggregations
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.{AnalyticsGraph, GraphAnalyticsRejection, PropertiesStatistics}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.{Decoder, JsonObject}
import monix.bio.{IO, Task}

trait GraphAnalytics {

  /**
    * The relationship statistics between different types for the passed ''projectRef''
    */
  def relationships(projectRef: ProjectRef): IO[GraphAnalyticsRejection, AnalyticsGraph]

  /**
    * The properties statistics of the passed ''project'' and type ''tpe''.
    */
  def properties(projectRef: ProjectRef, tpe: IdSegment): IO[GraphAnalyticsRejection, PropertiesStatistics]

}

object GraphAnalytics {

  final def apply(
      client: ElasticSearchClient,
      fetchContext: FetchContext[GraphAnalyticsRejection]
  )(implicit config: TermAggregationsConfig): Task[GraphAnalytics] =
    for {
      script <- scriptContent
      _      <- client.createScript(updateRelationshipsScriptId, script)
    } yield new GraphAnalytics {

      private val expandIri: ExpandIri[InvalidPropertyType] = new ExpandIri(InvalidPropertyType.apply)

      private def propertiesAggQueryFor(tpe: Iri)                                                     =
        propertiesAggQuery(config).map(_.replace("@type" -> "{{type}}", tpe))

      override def relationships(projectRef: ProjectRef): IO[GraphAnalyticsRejection, AnalyticsGraph] =
        for {
          _     <- fetchContext.onRead(projectRef)
          query <- relationshipsAggQuery(config)
          stats <- client
                     .searchAs[AnalyticsGraph](QueryBuilder(query), idx(projectRef).value, Query.Empty)
                     .mapError(err => WrappedElasticSearchRejection(WrappedElasticSearchClientError(err)))
        } yield stats

      override def properties(
          projectRef: ProjectRef,
          tpe: IdSegment
      ): IO[GraphAnalyticsRejection, PropertiesStatistics] = {

        def search(tpe: Iri, idx: IndexLabel, query: JsonObject) = {
          implicit val d: Decoder[PropertiesStatistics] = propertiesDecoderFromEsAggregations(tpe)
          client
            .searchAs[PropertiesStatistics](QueryBuilder(query).withTotalHits(true), idx.value, Query.Empty)
            .mapError(err => WrappedElasticSearchRejection(WrappedElasticSearchClientError(err)))
        }

        for {
          pc     <- fetchContext.onRead(projectRef)
          tpeIri <- expandIri(tpe, pc)
          query  <- propertiesAggQueryFor(tpeIri)
          stats  <- search(tpeIri, idx(projectRef), query)
        } yield stats

      }
    }

  private[analytics] def toPaths(key: String): Either[String, NonEmptySeq[Iri]] =
    key.split(" / ").toVector.foldM(Vector.empty[Iri])((acc, k) => Iri.absolute(k).map(acc :+ _)).flatMap {
      case Seq(first, tail @ _*) => Right(NonEmptySeq(first, tail))
      case _                     => Left("Empty Path")
    }

  private[analytics] def idx(projectRef: ProjectRef): IndexLabel =
    IndexLabel.unsafe(s"${UrlUtils.encode(projectRef.toString)}_graph_analytics")

  private[analytics] def projectionId(projectRef: ProjectRef): String =
    s"graph_analytics-$projectRef"

  private[analytics] def name(iri: Iri): String =
    (iri.fragment orElse iri.lastSegment) getOrElse iri.toString
}
