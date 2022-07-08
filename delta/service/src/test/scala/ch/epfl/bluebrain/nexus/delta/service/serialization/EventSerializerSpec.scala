package ch.epfl.bluebrain.nexus.delta.service.serialization

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent.{ResolverCreated, ResolverDeprecated, ResolverTagAdded, ResolverUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{Priority, ResolverEvent, ResolverType}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.EventSerializerBehaviours
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import io.circe.Json
import org.scalatest.CancelAfterFailure
import org.scalatest.flatspec.AnyFlatSpecLike

import java.time.Instant

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

  override val serializer = new EventSerializer
  val instant: Instant    = Instant.EPOCH
  val rev: Long           = 1L

  val realm: Label = Label.unsafe("myrealm")

  val subject: Subject        = User("username", realm)
  val group: Identity         = Group("group", realm)
  val authenticated: Identity = Authenticated(realm)

  val org: Label  = Label.unsafe("myorg")
  val proj: Label = Label.unsafe("myproj")
  val projectRef  = ProjectRef(org, proj)
  val myId        = nxv + "myId"

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

  "An EventSerializer" should behave like eventToJsonSerializer("resolver", resolversMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("resolver", resolversMapping)
}
