package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.Interval
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjectionFields.{ElasticSearchProjectionFields, SparqlProjectionFields}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{CrossProjectSource, ProjectSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSourceFields.{CrossProjectSourceFields, ProjectSourceFields}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{permissions, CompositeViewFields, CompositeViewValue}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, NonEmptySet}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.{Json, JsonObject}
import monix.execution.Scheduler
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._

import scala.concurrent.duration._
import java.time.Instant
import java.util.UUID

trait CompositeViewsFixture {

  val uuid                   = UUID.randomUUID()
  implicit val uuidF: UUIDF  = UUIDF.fixed(uuid)
  implicit val sc: Scheduler = Scheduler.global

  val epoch       = Instant.EPOCH
  val epochPlus10 = Instant.EPOCH.plusMillis(10L)
  val subject     = User("myuser", Label.unsafe("myrealm"))
  val id          = iri"http://localhost/${UUID.randomUUID()}"
  val project     = ProjectGen.project("myorg", "myproj")
  val projectRef  = project.ref
  val source      = Json.obj()
  val source2     = Json.obj("key" -> Json.fromInt(1))

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

  val esProjectionFields         = ElasticSearchProjectionFields(
    Some(iri"http://example.com/es-projection"),
    "SELECT * WHERE {?s ?p ?p}",
    JsonObject(),
    Json.obj(),
    Some(JsonObject())
  )
  val blazegraphProjectionFields = SparqlProjectionFields(
    Some(iri"http://example.com/blazegraph-projection"),
    "SELECT * WHERE {?s ?p ?p}"
  )

  val viewFields = CompositeViewFields(
    NonEmptySet.of(projectFields, crossProjectFields),
    NonEmptySet.of(esProjectionFields, blazegraphProjectionFields),
    Interval(1.minute)
  )

  val updatedFields = viewFields.copy(rebuildStrategy = Interval(2.minutes))

  val projectSource = ProjectSource(
    iri"http://example.com/project-source",
    uuid,
    Set.empty,
    Set.empty,
    None,
    false
  )

  val crossProjectSource = CrossProjectSource(
    iri"http://example.com/cross-project-source",
    uuid,
    Set.empty,
    Set.empty,
    None,
    false,
    ProjectRef(Label.unsafe("org"), Label.unsafe("otherproject")),
    identities = Set(Identity.Anonymous)
  )

  val esProjection         = ElasticSearchProjection(
    iri"http://example.com/es-projection",
    uuid,
    "SELECT * WHERE {?s ?p ?p}",
    Set.empty,
    Set.empty,
    None,
    false,
    false,
    permissions.query,
    false,
    JsonObject(),
    Some(JsonObject()),
    Json.obj()
  )
  val blazegraphProjection = SparqlProjection(
    iri"http://example.com/blazegraph-projection",
    uuid,
    "SELECT * WHERE {?s ?p ?p}",
    Set.empty,
    Set.empty,
    None,
    false,
    false,
    permissions.query
  )

  val viewValue    = CompositeViewValue(
    NonEmptySet.of(projectSource, crossProjectSource),
    NonEmptySet.of(esProjection, blazegraphProjection),
    Interval(1.minute)
  )
  val updatedValue = viewValue.copy(rebuildStrategy = Interval(2.minutes))

}
