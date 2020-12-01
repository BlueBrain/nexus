package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Projects, SchemaImports}
import monix.bio.UIO

object SchemaSetup {

  /**
    * Set up Schemas, populate some data and then eventually apply some deprecation.
    *
    * @param schemasToCreate    Schemas to create
    * @param schemasToDeprecate Schemas to deprecate
    */
  def init(
      orgs: Organizations,
      projects: Projects,
      schemasToCreate: List[Schema],
      schemasToDeprecate: List[Schema] = List.empty
  )(implicit
      clock: Clock[UIO],
      uuidf: UUIDF,
      rcr: RemoteContextResolution,
      subject: Subject
  ): UIO[SchemasDummy] =
    (for {
      resolvers <- ResolversDummy(projects)
      s         <- SchemasDummy(orgs, projects, SchemaImports(resolvers))
      // Creating schemas
      _         <- schemasToCreate.traverse(schema => s.create(IriSegment(schema.id), schema.project, schema.source))
      // Deprecating schemas
      _         <- schemasToDeprecate.traverse(schema => s.deprecate(IriSegment(schema.id), schema.project, 1L))
    } yield s).hideErrorsWith(r => new IllegalStateException(r.reason))

}
