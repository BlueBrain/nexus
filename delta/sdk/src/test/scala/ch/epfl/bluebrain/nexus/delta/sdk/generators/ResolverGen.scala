package ch.epfl.bluebrain.nexus.delta.sdk.generators

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverState.Current
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues}
import io.circe.Json
import io.circe.syntax._
import org.scalatest.OptionValues

object ResolverGen extends OptionValues with IOValues with CirceLiteral {

  /**
    * Generate a ResolverResource for the given parameters
    */
  def resolverResourceFor(
      id: Iri,
      project: Project,
      value: ResolverValue,
      source: Json,
      tags: Map[Label, Long] = Map.empty,
      rev: Long = 1L,
      subject: Subject = Anonymous,
      deprecated: Boolean = false
  ): ResolverResource =
    Current(id: Iri, project.ref, value, source, tags, rev, deprecated, Instant.EPOCH, subject, Instant.EPOCH, subject)
      .toResource(project.apiMappings, project.base)
      .value

  /**
    * Generate a valid json source from resolver id and value
    */
  def sourceFrom(id: Iri, resolverValue: ResolverValue): Json =
    Json.obj("@id" -> id.asJson).deepMerge(sourceWithoutId(resolverValue))

  /**
    * Generate a valid json source from resolver value and omitting an id
    */
  def sourceWithoutId(resolverValue: ResolverValue): Json = {
    {
      resolverValue match {
        case InProjectValue(priority)                                                 =>
          Json.obj(
            "@type"    -> Json.arr(Json.fromString("InProject")),
            "priority" -> Json.fromInt(priority.value)
          )
        case CrossProjectValue(priority, resourceTypes, projects, identityResolution) =>
          Json
            .obj(
              "@type"         -> Json.arr(Json.fromString("CrossProject")),
              "priority"      -> Json.fromInt(priority.value),
              "resourceTypes" -> resourceTypes.asJson,
              "projects"      -> projects.asJson
            )
            .deepMerge(identityResolution.asJson)
      }
    }.addContext(contexts.resolvers)
  }

}
