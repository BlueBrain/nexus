package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ValidationReport
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.resources.SchemaClaim.SubmitOnDefinedSchema
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidationResult._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.SchemaIsMandatory
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.Json
import munit.Location

class SchemaClaimSuite extends NexusSuite {

  private val project       = ProjectRef.unsafe("org", "proj")
  private val definedSchema = ResourceRef.Revision(nxv + "schema", 1)
  private val unconstrained = ResourceRef.Revision(Vocabulary.schemas.resources, 1)

  private val subject = User("myuser", Label.unsafe("myrealm"))
  private val caller  = Caller(subject, Set.empty)

  private val noValidation = NoValidation(project)
  private val validated    = Validated(project, definedSchema, ValidationReport.unsafe(conforms = true, 42, Json.Null))

  private val schemaIsMandatory = SchemaIsMandatory(project)

  private def submitOnDefinedSchema: SubmitOnDefinedSchema = (_, _, _) => IO.pure(validated)

  private def assertValidation(claim: SchemaClaim)(implicit location: Location) =
    claim.validate(enforceSchema = true)(submitOnDefinedSchema).assertEquals(validated) >>
      claim.validate(enforceSchema = false)(submitOnDefinedSchema).assertEquals(validated)

  private def assetNoValidation(claim: SchemaClaim)(implicit location: Location) =
    claim.validate(enforceSchema = true)(submitOnDefinedSchema).assertEquals(noValidation) >>
      claim.validate(enforceSchema = true)(submitOnDefinedSchema).assertEquals(noValidation)

  private def failWhenEnforceSchema(claim: SchemaClaim)(implicit location: Location) =
    claim.validate(enforceSchema = true)(submitOnDefinedSchema).interceptEquals(schemaIsMandatory) >>
      claim.validate(enforceSchema = false)(submitOnDefinedSchema).assertEquals(noValidation)

  test("Create with a schema runs validation") {
    val claim = SchemaClaim.onCreate(project, definedSchema, caller)
    assertValidation(claim)
  }

  test("Create unconstrained fails or skip validation") {
    val claim = SchemaClaim.onCreate(project, unconstrained, caller)
    failWhenEnforceSchema(claim)
  }

  test("Update to a defined schema runs validation") {
    val claim = SchemaClaim.onUpdate(project, definedSchema, definedSchema, caller)
    assertValidation(claim)
  }

  test("Update to unconstrained fails or skip validation") {
    val claim = SchemaClaim.onUpdate(project, unconstrained, definedSchema, caller)
    failWhenEnforceSchema(claim)
  }

  test("Keeping unconstrained skips validation") {
    val claim = SchemaClaim.onUpdate(project, unconstrained, unconstrained, caller)
    assetNoValidation(claim)
  }

}
