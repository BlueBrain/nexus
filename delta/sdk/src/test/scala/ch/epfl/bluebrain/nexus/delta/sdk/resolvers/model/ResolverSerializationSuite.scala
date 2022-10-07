package ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverEvent.{ResolverCreated, ResolverDeprecated, ResolverTagAdded, ResolverUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import io.circe.Json

import java.time.Instant

class ResolverSerializationSuite extends SerializationSuite {

  private val sseEncoder = ResolverEvent.sseEncoder

  val instant: Instant = Instant.EPOCH
  val rev: Long        = 1L

  val realm: Label = Label.unsafe("myrealm")

  val subject: Subject        = User("username", realm)
  val group: Identity         = Group("group", realm)
  val authenticated: Identity = Authenticated(realm)

  val org: Label  = Label.unsafe("myorg")
  val proj: Label = Label.unsafe("myproj")
  val projectRef  = ProjectRef(org, proj)
  val myId        = nxv + "myId"

  val inProjectValue: InProjectValue        = InProjectValue(Priority.unsafe(42))
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

  private val resolversMapping = Map(
    ResolverCreated(
      myId,
      projectRef,
      inProjectValue,
      Json.obj("resolver" -> Json.fromString("created")),
      1,
      instant,
      subject
    ) -> loadEvents("resolvers", "resolver-in-project-created.json"),
    ResolverCreated(
      myId,
      projectRef,
      crossProjectValue1,
      Json.obj("resolver" -> Json.fromString("created")),
      1,
      instant,
      subject
    ) -> loadEvents("resolvers", "resolver-cross-project-created-1.json"),
    ResolverCreated(
      myId,
      projectRef,
      crossProjectValue2,
      Json.obj("resolver" -> Json.fromString("created")),
      1,
      instant,
      subject
    ) -> loadEvents("resolvers", "resolver-cross-project-created-2.json"),
    ResolverUpdated(
      myId,
      projectRef,
      inProjectValue,
      Json.obj("resolver" -> Json.fromString("updated")),
      2,
      instant,
      subject
    ) -> loadEvents("resolvers", "resolver-in-project-updated.json"),
    ResolverUpdated(
      myId,
      projectRef,
      crossProjectValue1,
      Json.obj("resolver" -> Json.fromString("updated")),
      2,
      instant,
      subject
    ) -> loadEvents("resolvers", "resolver-cross-project-updated-1.json"),
    ResolverUpdated(
      myId,
      projectRef,
      crossProjectValue2,
      Json.obj("resolver" -> Json.fromString("updated")),
      2,
      instant,
      subject
    ) -> loadEvents("resolvers", "resolver-cross-project-updated-2.json"),
    ResolverTagAdded(
      myId,
      projectRef,
      ResolverType.InProject,
      1,
      UserTag.unsafe("mytag"),
      3,
      instant,
      subject
    ) -> loadEvents("resolvers", "resolver-tagged.json"),
    ResolverDeprecated(
      myId,
      projectRef,
      ResolverType.InProject,
      4,
      instant,
      subject
    ) -> loadEvents("resolvers", "resolver-deprecated.json")
  )

  resolversMapping.foreach { case (event, (database, sse)) =>
    test(s"Correctly serialize ${event.getClass.getName}") {
      ResolverEvent.serializer.codec(event).equalsIgnoreArrayOrder(database)
    }

    test(s"Correctly deserialize ${event.getClass.getName}") {
      assertEquals(ResolverEvent.serializer.codec.decodeJson(database), Right(event))
    }

    test(s"Correctly serialize ${event.getClass.getName} as an SSE") {
      sseEncoder.toSse
        .decodeJson(database)
        .assertRight(SseData(ClassUtils.simpleName(event), Some(ProjectRef(org, proj)), sse))
    }
  }

  private val statesMapping = Map(
    inProjectValue     -> jsonContentOf("/resolvers/resolver-in-project-state.json"),
    crossProjectValue1 -> jsonContentOf("/resolvers/resolver-cross-project-state-1.json"),
    crossProjectValue2 -> jsonContentOf("/resolvers/resolver-cross-project-state-2.json")
  ).map { case (k, v) =>
    ResolverState(
      myId,
      projectRef,
      k,
      Json.obj("resolver"          -> Json.fromString("value")),
      Tags(UserTag.unsafe("mytag") -> 3),
      rev = 1,
      deprecated = false,
      createdAt = instant,
      createdBy = subject,
      updatedAt = instant,
      updatedBy = subject
    ) -> v
  }

  statesMapping.foreach { case (state, json) =>
    test(s"Correctly serialize state ${state.value.tpe}") {
      ResolverState.serializer.codec(state).equalsIgnoreArrayOrder(json)
    }

    test(s"Correctly deserialize ${state.value.tpe}") {
      assertEquals(ResolverState.serializer.codec.decodeJson(json), Right(state))
    }
  }
}
