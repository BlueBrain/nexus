package ch.epfl.bluebrain.nexus.delta.sdk.error

import ch.epfl.bluebrain.nexus.delta.kernel.error.FormatError
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission

object FormatErrors {

  /**
    * Name formatting error, returned in cases where a Name could not be constructed from a String.
    *
    * @param details
    *   possible additional details that may be interesting to provide to the caller
    */
  final case class IllegalNameFormatError(details: Option[String] = None)
      extends FormatError(
        s"The provided string did not match the expected name format '${Name.regex.regex}'.",
        details
      )

  /**
    * Permission formatting error, returned in cases where a Permission could not be constructed from a String.
    *
    * @param details
    *   possible additional details that may be interesting to provide to the caller
    */
  final case class IllegalPermissionFormatError(details: Option[String] = None)
      extends FormatError(
        s"The provided string did not match the expected permission format '${Permission.regex.regex}'.",
        details
      )

  /**
    * AclAddress formatting error, returned in cases where an AclAddress could not be constructed from a String.
    *
    * @param details
    *   possible additional details that may be interesting to provide to the caller
    */
  final case class IllegalAclAddressFormatError(details: Option[String] = None)
      extends FormatError(
        s"The provided string did not match any of the expected Acl Address formats: '/', '${AclAddress.orgAddressRegex.regex}', '${AclAddress.projAddressRegex.regex}'.",
        details
      )

  /**
    * Prefix Mapping Iri formatting error, returned in cases where a PrefixIri could not be constructed from an Iri.
    *
    * @param details
    *   possible additional details that may be interesting to provide to the caller
    */
  final case class IllegalPrefixIRIFormatError(iri: Iri, details: Option[String] = None)
      extends FormatError(s"The provided iri '$iri' does not end with '/' or '#'", details)

  /**
    * Identity iri formatting error, returned in cases where an Identity could not be constructed from an Iri.
    *
    * @param details
    *   possible additional details that may be interesting to provide to the caller
    */
  final case class IllegalIdentityIriFormatError(iri: Iri, details: Option[String] = None)
      extends FormatError(s"The provided iri '$iri' does not represent an identity", details)

  /**
    * Subject iri formatting error, returned in cases where an Subject could not be constructed from an Iri.
    *
    * @param details
    *   possible additional details that may be interesting to provide to the caller
    */
  final case class IllegalSubjectIriFormatError(iri: Iri, details: Option[String] = None)
      extends FormatError(s"The provided iri '$iri' does not represent a subject", details)

  /**
    * Iri formatting error, returned in cases where a Iri could not be constructed from an string.
    *
    * @param details
    *   possible additional details that may be interesting to provide to the caller
    */
  final case class IllegalIRIFormatError(value: String, details: Option[String] = None)
      extends FormatError(s"The provided '$value' is not an Iri", details)

  /**
    * Absolute IRI formatting error, returned in cases where an Iri is not absolute.
    */
  final case class IllegalAbsoluteIRIFormatError(value: String)
      extends FormatError(s"The provided '$value' is not an absolute Iri")

  /**
    * Resolver priority interval error, returned in cases where the provided value is out of bounds.
    *
    * @param details
    *   possible additional details that may be interesting to provide to the caller
    */
  final case class ResolverPriorityIntervalError(value: Int, min: Int, max: Int, details: Option[String] = None)
      extends FormatError(s"The provided priority '$value' is not between '$min' and '$max' included", details)

}
