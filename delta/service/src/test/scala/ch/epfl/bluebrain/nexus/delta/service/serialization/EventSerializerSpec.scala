package ch.epfl.bluebrain.nexus.delta.service.serialization

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ResourceGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent.{ProjectCreated, ProjectDeprecated, ProjectUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, PrefixIri, ProjectEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent.{ResolverCreated, ResolverDeprecated, ResolverTagAdded, ResolverUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{Priority, ResolverEvent, ResolverType}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent.{ResourceCreated, ResourceDeprecated, ResourceTagAdded, ResourceTagDeleted, ResourceUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent.{SchemaCreated, SchemaDeprecated, SchemaTagAdded, SchemaTagDeleted, SchemaUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.EventSerializerBehaviours
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Revision
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import io.circe.Json
import org.scalatest.CancelAfterFailure
import org.scalatest.flatspec.AnyFlatSpecLike

import java.time.Instant
import java.util.UUID

/*
scalafmt: {
  style = defaultWithAlign
  maxColumn = 150
}
 */
class EventSerializerSpec
    extends TestKit(ActorSystem("EventSerializerSpec"))
    with EventSerializerBehaviours
    with AnyFlatSpecLike
    with TestHelpers
    with CirceLiteral
    with CancelAfterFailure
    with IOValues {
  implicit private val cl: ClassLoader = getClass.getClassLoader

  override val serializer = new EventSerializer
  val instant: Instant    = Instant.EPOCH
  val rev: Long           = 1L

  val realm: Label = Label.unsafe("myrealm")

  val subject: Subject        = User("username", realm)
  val anonymous: Subject      = Anonymous
  val group: Identity         = Group("group", realm)
  val authenticated: Identity = Authenticated(realm)

  val org: Label          = Label.unsafe("myorg")
  val orgUuid: UUID       = UUID.fromString("b6bde92f-7836-4da6-8ead-2e0fd516ebe7")
  val description: String = "some description"

  val proj: Label              = Label.unsafe("myproj")
  val projUuid: UUID           = UUID.fromString("fe1301a6-a105-4966-84af-32723fd003d2")
  val apiMappings: ApiMappings = ApiMappings("nxv" -> nxv.base)
  val base: PrefixIri          = PrefixIri.unsafe(schemas.base)
  val vocab: PrefixIri         = PrefixIri.unsafe(nxv.base)
  val projectRef               = ProjectRef(org, proj)
  val myId                     = nxv + "myId"
  implicit def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.shacl           -> ContextValue.fromFile("contexts/shacl.json").accepted,
      contexts.schemasMetadata -> ContextValue.fromFile("contexts/schemas-metadata.json").accepted
    )
  val resource                              = ResourceGen.resource(myId, projectRef, jsonContentOf("resources/resource.json", "id" -> myId))
  val scheme = SchemaGen.schema(
    myId,
    projectRef,
    jsonContentOf("resources/schema.json").addContext(contexts.shacl, contexts.schemasMetadata) deepMerge json"""{"@id": "$myId"}"""
  )

  val inProjectValue: InProjectValue = InProjectValue(Priority.unsafe(42))
  val crossProjectValue1: CrossProjectValue = CrossProjectValue(
    Priority.unsafe(42),
    Set(schemas.projects, schemas.resources),
    NonEmptyList.of(projectRef, ProjectRef.unsafe("org2", "proj2")),
    ProvidedIdentities(Set(subject, Anonymous, group, authenticated))
  )
  val crossProjectValue2: CrossProjectValue = CrossProjectValue(
    Priority.unsafe(42),
    Set(schemas.projects, schemas.resources),
    NonEmptyList.of(projectRef, ProjectRef.unsafe("org2", "proj2")),
    UseCurrentCaller
  )

  val resolversMapping: Map[ResolverEvent, Json] = Map(
    ResolverCreated(
      myId,
      projectRef,
      inProjectValue,
      Json.obj("resolver" -> Json.fromString("created")),
      1L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resolver-in-project-created.json"),
    ResolverCreated(
      myId,
      projectRef,
      crossProjectValue1,
      Json.obj("resolver" -> Json.fromString("created")),
      1L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resolver-cross-project-created-1.json"),
    ResolverCreated(
      myId,
      projectRef,
      crossProjectValue2,
      Json.obj("resolver" -> Json.fromString("created")),
      1L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resolver-cross-project-created-2.json"),
    ResolverUpdated(
      myId,
      projectRef,
      inProjectValue,
      Json.obj("resolver" -> Json.fromString("updated")),
      2L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resolver-in-project-updated.json"),
    ResolverUpdated(
      myId,
      projectRef,
      crossProjectValue1,
      Json.obj("resolver" -> Json.fromString("updated")),
      2L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resolver-cross-project-updated-1.json"),
    ResolverUpdated(
      myId,
      projectRef,
      crossProjectValue2,
      Json.obj("resolver" -> Json.fromString("updated")),
      2L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resolver-cross-project-updated-2.json"),
    ResolverTagAdded(
      myId,
      projectRef,
      ResolverType.InProject,
      1L,
      UserTag.unsafe("mytag"),
      3L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resolver-tagged.json"),
    ResolverDeprecated(
      myId,
      projectRef,
      ResolverType.InProject,
      4L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resolver-deprecated.json")
  )

  val schemasMapping: Map[SchemaEvent, Json] = Map(
    SchemaCreated(
      myId,
      projectRef,
      scheme.source,
      scheme.compacted,
      scheme.expanded,
      1L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/schema-created.json"),
    SchemaUpdated(
      myId,
      projectRef,
      scheme.source,
      scheme.compacted,
      scheme.expanded,
      2L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/schema-updated.json"),
    SchemaTagAdded(
      myId,
      projectRef,
      1L,
      UserTag.unsafe("mytag"),
      3L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/schema-tagged.json"),
    SchemaTagDeleted(
      myId,
      projectRef,
      UserTag.unsafe("mytag"),
      3L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/schema-tag-deleted.json"),
    SchemaDeprecated(
      myId,
      projectRef,
      4L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/schema-deprecated.json")
  )

  val resourcesMapping: Map[ResourceEvent, Json] = Map(
    ResourceCreated(
      myId,
      projectRef,
      Revision(schemas.resources, 1),
      projectRef,
      Set(schema.Person),
      resource.source,
      resource.compacted,
      resource.expanded,
      1L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resource-created.json"),
    ResourceUpdated(
      myId,
      projectRef,
      Revision(schemas.resources, 1),
      projectRef,
      Set(schema.Person),
      resource.source,
      resource.compacted,
      resource.expanded,
      2L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resource-updated.json"),
    ResourceTagAdded(
      myId,
      projectRef,
      Set(schema.Person),
      1L,
      UserTag.unsafe("mytag"),
      3L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resource-tagged.json"),
    ResourceDeprecated(
      myId,
      projectRef,
      Set(schema.Person),
      4L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resource-deprecated.json"),
    ResourceTagDeleted(
      myId,
      projectRef,
      Set(schema.Person),
      UserTag.unsafe("mytag"),
      5L,
      instant,
      subject
    ) -> jsonContentOf("/serialization/resource-tag-deleted.json")
  )

  val projectsMapping: Map[ProjectEvent, Json] = Map(
    ProjectCreated(
      label = proj,
      uuid = projUuid,
      organizationLabel = org,
      organizationUuid = orgUuid,
      rev = rev,
      description = Some(description),
      apiMappings = apiMappings,
      base = base,
      vocab = vocab,
      instant = instant,
      subject = subject
    ) -> jsonContentOf("/serialization/project-created.json"),
    ProjectUpdated(
      label = proj,
      uuid = projUuid,
      organizationLabel = org,
      organizationUuid = orgUuid,
      rev = rev,
      description = Some(description),
      apiMappings = apiMappings,
      base = base,
      vocab = vocab,
      instant = instant,
      subject = subject
    ) -> jsonContentOf("/serialization/project-updated.json"),
    ProjectDeprecated(
      label = proj,
      uuid = projUuid,
      organizationLabel = org,
      organizationUuid = orgUuid,
      rev = rev,
      instant = instant,
      subject = subject
    ) -> jsonContentOf("/serialization/project-deprecated.json")
  )

  "An EventSerializer" should behave like eventToJsonSerializer("project", projectsMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("project", projectsMapping)
  "An EventSerializer" should behave like eventToJsonSerializer("resolver", resolversMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("resolver", resolversMapping)
  "An EventSerializer" should behave like eventToJsonSerializer("resource", resourcesMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("resource", resourcesMapping)
  "An EventSerializer" should behave like eventToJsonSerializer("schema", schemasMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("schema", schemasMapping)

}
