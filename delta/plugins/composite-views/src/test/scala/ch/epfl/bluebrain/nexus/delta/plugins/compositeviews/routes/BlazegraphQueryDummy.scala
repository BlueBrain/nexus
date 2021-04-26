package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{SparqlQuery, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.BlazegraphQuery
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.Json
import monix.bio.IO

import scala.xml.NodeSeq

class BlazegraphQueryDummy(
    val commonNsSparqlResults: Map[SparqlQuery, SparqlResults],
    val projectionSparqlResults: Map[(IdSegment, SparqlQuery), SparqlResults],
    val projectionsSparqlResults: Map[SparqlQuery, SparqlResults],
    val commonNsXml: Map[SparqlQuery, NodeSeq],
    val projectionXml: Map[(IdSegment, SparqlQuery), NodeSeq],
    val projectionsXml: Map[SparqlQuery, NodeSeq],
    val commonNsJsonLdConstruct: Map[SparqlQuery, Json],
    val projectionJsonLdConstruct: Map[(IdSegment, SparqlQuery), Json],
    val projectionsJsonLdConstruct: Map[SparqlQuery, Json],
    val commonNsNTriplesConstruct: Map[SparqlQuery, NTriples],
    val projectionNTriplesConstruct: Map[(IdSegment, SparqlQuery), NTriples],
    val projectionsNTriplesConstruct: Map[SparqlQuery, NTriples],
    val commonNsXmlConstruct: Map[SparqlQuery, NodeSeq],
    val projectionXmlConstruct: Map[(IdSegment, SparqlQuery), NodeSeq],
    val projectionsXmlConstruct: Map[SparqlQuery, NodeSeq]
) extends BlazegraphQuery {

  override def queryResults(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults] =
    IO.pure(commonNsSparqlResults(query))

  override def queryResults(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults] =
    IO.pure(projectionSparqlResults(projectionId -> query))

  override def queryProjectionsResults(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults] =
    IO.pure(projectionsSparqlResults(query))

  override def queryXml(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq] =
    IO.pure(commonNsXml(query))

  override def queryXml(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq] =
    IO.pure(projectionXml(projectionId -> query))

  override def queryProjectionsXml(id: IdSegment, project: ProjectRef, query: SparqlQuery)(implicit
      caller: Caller
  ): IO[CompositeViewRejection, NodeSeq]                          =
    IO.pure(projectionsXml(query))

  override def constructQueryJsonLd(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlConstructQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, Json] =
    IO.pure(commonNsJsonLdConstruct(query))

  override def constructQueryJsonLd(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: SparqlQuery.SparqlConstructQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, Json] =
    IO.pure(projectionJsonLdConstruct(projectionId -> query))

  override def constructQueryProjectionsJsonLd(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery.SparqlConstructQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, Json] =
    IO.pure(projectionsJsonLdConstruct(query))

  override def constructQueryNTriples(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlConstructQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NTriples] =
    IO.pure(commonNsNTriplesConstruct(query))

  override def constructQueryNTriples(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: SparqlConstructQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NTriples] =
    IO.pure(projectionNTriplesConstruct(projectionId -> query))

  override def constructQueryProjectionsNTriples(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlConstructQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NTriples] =
    IO.pure(projectionsNTriplesConstruct(query))

  override def constructQueryXml(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlConstructQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq] =
    IO.pure(commonNsXmlConstruct(query))

  override def constructQueryXml(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: SparqlConstructQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq] =
    IO.pure(projectionXmlConstruct(projectionId -> query))

  override def constructQueryProjectionsXml(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlConstructQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq] =
    IO.pure(projectionsXmlConstruct(query))

}
