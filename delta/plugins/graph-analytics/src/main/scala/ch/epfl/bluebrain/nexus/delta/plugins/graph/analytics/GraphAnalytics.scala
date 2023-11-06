package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import akka.http.scaladsl.model.Uri.Query
import cats.data.NonEmptySeq
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.toCatsIOOps
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel, QueryBuilder}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.WrappedElasticSearchClientError
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.config.GraphAnalyticsConfig.TermAggregationsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.{propertiesAggQuery, relationshipsAggQuery}
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

trait GraphAnalytics {

  /**
    * The relationship statistics between different types for the passed ''projectRef''
    */
  def relationships(projectRef: ProjectRef): IO[AnalyticsGraph]

  /**
    * The properties statistics of the passed ''project'' and type ''tpe''.
    */
  def properties(projectRef: ProjectRef, tpe: IdSegment): IO[PropertiesStatistics]

}

object GraphAnalytics {

  final def apply(
      client: ElasticSearchClient,
      fetchContext: FetchContext[GraphAnalyticsRejection],
      prefix: String,
      config: TermAggregationsConfig
  ): GraphAnalytics =
    new GraphAnalytics {

      private val expandIri: ExpandIri[InvalidPropertyType] = new ExpandIri(InvalidPropertyType.apply)

      private def propertiesAggQueryFor(tpe: Iri)                            =
        propertiesAggQuery(config).map(_.replace("@type" -> "{{type}}", tpe))

      override def relationships(projectRef: ProjectRef): IO[AnalyticsGraph] =
        for {
          _     <- fetchContext.onRead(projectRef).toCatsIO
          query <- relationshipsAggQuery(config)
          stats <- client
                     .searchAs[AnalyticsGraph](QueryBuilder(query), index(prefix, projectRef).value, Query.Empty)
                     .mapError(err => WrappedElasticSearchRejection(WrappedElasticSearchClientError(err)))
                     .toCatsIO
        } yield stats

      override def properties(
          projectRef: ProjectRef,
          tpe: IdSegment
      ): IO[PropertiesStatistics] = {

        def search(tpe: Iri, idx: IndexLabel, query: JsonObject) = {
          implicit val d: Decoder[PropertiesStatistics] = propertiesDecoderFromEsAggregations(tpe)
          client
            .searchAs[PropertiesStatistics](QueryBuilder(query).withTotalHits(true), idx.value, Query.Empty)
            .mapError(err => WrappedElasticSearchRejection(WrappedElasticSearchClientError(err)))
            .toCatsIO
        }

        for {
          pc     <- fetchContext.onRead(projectRef).toCatsIO
          tpeIri <- expandIri(tpe, pc).toCatsIO
          query  <- propertiesAggQueryFor(tpeIri)
          stats  <- search(tpeIri, index(prefix, projectRef), query)
        } yield stats

      }
    }

  private[analytics] def toPaths(key: String): Either[String, NonEmptySeq[Iri]] =
    key.split(" / ").toVector.foldM(Vector.empty[Iri])((acc, k) => Iri.absolute(k).map(acc :+ _)).flatMap {
      case Seq(first, tail @ _*) => Right(NonEmptySeq(first, tail))
      case _                     => Left("Empty Path")
    }

  private[analytics] def index(prefix: String, ref: ProjectRef): IndexLabel =
    IndexLabel.unsafe(s"${prefix}_ga_${ref.organization}_${ref.project}")

  private[analytics] def projectionName(ref: ProjectRef): String = s"ga-$ref"

  private[analytics] def name(iri: Iri): String =
    (iri.fragment orElse iri.lastSegment) getOrElse iri.toString
}
