package ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
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
  val rev: Int         = 1

  val realm: Label = Label.unsafe("myrealm")

  val subject: Subject        = User("username", realm)
  val group: Identity         = Group("group", realm)
  val authenticated: Identity = Authenticated(realm)

  private val org        = Label.unsafe("myorg")
  private val proj       = Label.unsafe("myproj")
  private val projectRef = ProjectRef(org, proj)
  private val myId       = nxv + "myId"

  private val resolverName        = Some("resolverName")
  private val resolverDescription = Some("resolverDescription")

  val inProjectValue: InProjectValue            = InProjectValue(Priority.unsafe(42))
  val namedInProjectValue: InProjectValue       = InProjectValue(resolverName, resolverDescription, Priority.unsafe(42))
  val crossProjectValue1: CrossProjectValue     = CrossProjectValue(
    Priority.unsafe(42),
    Set(schemas.projects, schemas.resources),
    NonEmptyList.of(projectRef, ProjectRef.unsafe("org2", "proj2")),
    ProvidedIdentities(Set(subject, Anonymous, group, authenticated))
  )
  val crossProjectValue2: CrossProjectValue     = CrossProjectValue(
    Priority.unsafe(42),
    Set(schemas.projects, schemas.resources),
    NonEmptyList.of(projectRef, ProjectRef.unsafe("org2", "proj2")),
    UseCurrentCaller
  )
  val namedCrossProjectValue: CrossProjectValue = CrossProjectValue(
    resolverName,
    resolverDescription,
    Priority.unsafe(42),
    Set(schemas.projects, schemas.resources),
    NonEmptyList.of(projectRef, ProjectRef.unsafe("org2", "proj2")),
    ProvidedIdentities(Set(subject, Anonymous, group, authenticated))
  )

  private val created    =
    ResolverCreated(
      myId,
      projectRef,
      inProjectValue,
      Json.obj("resolver" -> Json.fromString("created")),
      1,
      instant,
      subject
    )
  private val created1   =
    ResolverCreated(
      myId,
      projectRef,
      crossProjectValue1,
      Json.obj("resolver" -> Json.fromString("created")),
      1,
      instant,
      subject
    )
  private val created2   =
    ResolverCreated(
      myId,
      projectRef,
      crossProjectValue2,
      Json.obj("resolver" -> Json.fromString("created")),
      1,
      instant,
      subject
    )
  private val updated    =
    ResolverUpdated(
      myId,
      projectRef,
      inProjectValue,
      Json.obj("resolver" -> Json.fromString("updated")),
      2,
      instant,
      subject
    )
  private val updated1   =
    ResolverUpdated(
      myId,
      projectRef,
      crossProjectValue1,
      Json.obj("resolver" -> Json.fromString("updated")),
      2,
      instant,
      subject
    )
  private val updated2   =
    ResolverUpdated(
      myId,
      projectRef,
      crossProjectValue2,
      Json.obj("resolver" -> Json.fromString("updated")),
      2,
      instant,
      subject
    )
  private val tagged     =
    ResolverTagAdded(
      myId,
      projectRef,
      ResolverType.InProject,
      1,
      UserTag.unsafe("mytag"),
      3,
      instant,
      subject
    )
  private val deprecated =
    ResolverDeprecated(
      myId,
      projectRef,
      ResolverType.InProject,
      4,
      instant,
      subject
    )

  private val createdNamedInProject    =
    ResolverCreated(
      myId,
      projectRef,
      namedInProjectValue,
      Json.obj("resolver" -> Json.fromString("created")),
      1,
      instant,
      subject
    )
  private val createdNamedCrossProject =
    ResolverCreated(
      myId,
      projectRef,
      namedCrossProjectValue,
      Json.obj("resolver" -> Json.fromString("created")),
      1,
      instant,
      subject
    )

  private val resolversMapping = List(
    (created, loadEvents("resolvers", "resolver-in-project-created.json")),
    (created1, loadEvents("resolvers", "resolver-cross-project-created-1.json")),
    (created2, loadEvents("resolvers", "resolver-cross-project-created-2.json")),
    (updated, loadEvents("resolvers", "resolver-in-project-updated.json")),
    (updated1, loadEvents("resolvers", "resolver-cross-project-updated-1.json")),
    (updated2, loadEvents("resolvers", "resolver-cross-project-updated-2.json")),
    (tagged, loadEvents("resolvers", "resolver-tagged.json")),
    (deprecated, loadEvents("resolvers", "resolver-deprecated.json")),
    (createdNamedInProject, loadEvents("resolvers", "resolver-in-project-created-named.json")),
    (createdNamedCrossProject, loadEvents("resolvers", "resolver-cross-project-created-named.json"))
  )

  resolversMapping.foreach { case (event, (database, sse)) =>
    test(s"Correctly serialize ${event.getClass.getName}") {
      assertOutputIgnoreOrder(ResolverEvent.serializer, event, database)
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
    inProjectValue     -> jsonContentOf("resolvers/resolver-in-project-state.json"),
    crossProjectValue1 -> jsonContentOf("resolvers/resolver-cross-project-state-1.json"),
    crossProjectValue2 -> jsonContentOf("resolvers/resolver-cross-project-state-2.json")
  ).map { case (k, v) =>
    ResolverState(
      myId,
      projectRef,
      k,
      Json.obj("resolver" -> Json.fromString("value")),
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
      assertOutputIgnoreOrder(ResolverState.serializer, state, json)
    }

    test(s"Correctly deserialize ${state.value.tpe}") {
      assertEquals(ResolverState.serializer.codec.decodeJson(json), Right(state))
    }
  }
}
