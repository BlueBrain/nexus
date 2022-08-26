package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig.ConstantStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.{RemoteSourceClientConfig, SourcesConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.Interval
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjectionFields.{ElasticSearchProjectionFields, SparqlProjectionFields}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{AccessToken, CrossProjectSource, ProjectSource, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSourceFields.{CrossProjectSourceFields, ProjectSourceFields, RemoteProjectSourceFields}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{permissions, CompositeViewFields, CompositeViewValue, TemplateSparqlConstructQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import io.circe.{Json, JsonObject}
import monix.bio.IO
import monix.execution.Scheduler

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

trait CompositeViewsFixture extends ConfigFixtures with EitherValuable {

  val crypto: Crypto = Crypto("changeme", "salt")

  val alwaysValidate: ValidateCompositeView = (_, _, _) => IO.unit

  val query =
    TemplateSparqlConstructQuery(
      "prefix p: <http://localhost/>\nCONSTRUCT{ {resource_id} p:transformed ?v } WHERE { {resource_id} p:predicate ?v}"
    ).rightValue

  val uuid                   = UUID.fromString("f8468909-a797-4b10-8b5f-000cba337bfa")
  implicit val uuidF: UUIDF  = UUIDF.fixed(uuid)
  implicit val sc: Scheduler = Scheduler.global

  val epoch           = Instant.EPOCH
  val epochPlus10     = Instant.EPOCH.plusMillis(10L)
  val subject         = User("myuser", Label.unsafe("myrealm"))
  val id              = iri"http://localhost/${UUID.randomUUID()}"
  val project         = ProjectGen.project("myorg", "myproj")
  val projectRef      = project.ref
  val otherProjectRef = ProjectGen.project("org", "otherproject").ref
  val source          = Json.obj()
  val source2         = Json.obj("key" -> Json.fromInt(1))

  val projectFields      = ProjectSourceFields(
    Some(iri"http://example.com/project-source"),
    Set.empty,
    Set.empty,
    None
  )
  val crossProjectFields = CrossProjectSourceFields(
    Some(iri"http://example.com/cross-project-source"),
    ProjectRef(Label.unsafe("org"), Label.unsafe("otherproject")),
    identities = Set(Identity.Anonymous)
  )

  val remoteProjectFields = RemoteProjectSourceFields(
    Some(iri"http://example.com/remote-project-source"),
    ProjectRef(Label.unsafe("org"), Label.unsafe("remoteproject")),
    Uri("http://example.com/remote-endpoint"),
    Some(Secret("secret token"))
  )

  val esProjectionFields         = ElasticSearchProjectionFields(
    Some(iri"http://example.com/es-projection"),
    query,
    None,
    JsonObject(),
    ContextObject(JsonObject.empty),
    Some(JsonObject())
  )
  val blazegraphProjectionFields = SparqlProjectionFields(
    Some(iri"http://example.com/blazegraph-projection"),
    query
  )

  val viewFields = CompositeViewFields(
    NonEmptySet.of(projectFields, crossProjectFields, remoteProjectFields),
    NonEmptySet.of(esProjectionFields, blazegraphProjectionFields),
    Some(Interval(1.minute))
  )

  val updatedFields = viewFields.copy(rebuildStrategy = Some(Interval(2.minutes)))

  val projectSource = ProjectSource(
    iri"http://example.com/project-source",
    uuid,
    Set.empty,
    Set.empty,
    None,
    false
  )

  val otherProject = ProjectRef(Label.unsafe("org"), Label.unsafe("otherproject"))

  val crossProjectSource = CrossProjectSource(
    iri"http://example.com/cross-project-source",
    uuid,
    Set.empty,
    Set.empty,
    None,
    false,
    otherProject,
    identities = Set(Identity.Anonymous)
  )

  val remoteProjectSource = RemoteProjectSource(
    iri"http://example.com/remote-project-source",
    uuid,
    Set.empty,
    Set.empty,
    None,
    false,
    ProjectRef(Label.unsafe("org"), Label.unsafe("remoteproject")),
    Uri("http://example.com/remote-endpoint"),
    Some(AccessToken(Secret("secret token")))
  )

  val esProjection         = ElasticSearchProjection(
    iri"http://example.com/es-projection",
    uuid,
    query,
    Set.empty,
    Set.empty,
    None,
    false,
    false,
    permissions.query,
    None,
    JsonObject(),
    Some(JsonObject()),
    ContextObject(JsonObject.empty)
  )
  val blazegraphProjection = SparqlProjection(
    iri"http://example.com/blazegraph-projection",
    uuid,
    query,
    Set.empty,
    Set.empty,
    None,
    false,
    false,
    permissions.query
  )

  val viewValue    = CompositeViewValue(
    NonEmptySet.of(projectSource, crossProjectSource, remoteProjectSource),
    NonEmptySet.of(esProjection, blazegraphProjection),
    Some(Interval(1.minute))
  )
  val updatedValue = viewValue.copy(rebuildStrategy = Some(Interval(2.minutes)))

  val config: CompositeViewsConfig = CompositeViewsConfig(
    SourcesConfig(1, 1.second, 3, ConstantStrategyConfig(1.second, 10)),
    "prefix",
    3,
    eventLogConfig,
    pagination,
    RemoteSourceClientConfig(httpClientConfig, 1.second, 1, 500.milliseconds),
    1.minute,
    1.minute
  )
}

object CompositeViewsFixture extends CompositeViewsFixture
