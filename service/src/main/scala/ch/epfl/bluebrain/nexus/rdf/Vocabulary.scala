package ch.epfl.bluebrain.nexus.rdf

import akka.http.scaladsl.model.Uri
import cats.Show

/**
  * Constant vocabulary values
  */
object Vocabulary {

  /**
    * Nexus vocabulary.
    */
  object nxv {
    implicit private[rdf] val base: Uri = Uri("https://bluebrain.github.io/nexus/vocabulary/")

    /**
      * @param suffix the segment to suffix to the base
      */
    def withSuffix(suffix: String): Uri = base.copy(path = base.path + suffix)

    // Metadata vocabulary
    val rev                   = PrefixMapping.segment("rev")
    val deprecated            = PrefixMapping.segment("deprecated")
    val createdAt             = PrefixMapping.segment("createdAt")
    val updatedAt             = PrefixMapping.segment("updatedAt")
    val createdBy             = PrefixMapping.segment("createdBy")
    val updatedBy             = PrefixMapping.segment("updatedBy")
    val constrainedBy         = PrefixMapping.segment("constrainedBy")
    val self                  = PrefixMapping.segment("self")
    val project               = PrefixMapping.segment("project")
    val total                 = PrefixMapping.segment("total")
    val results               = PrefixMapping.segment("results")
    val maxScore              = PrefixMapping.segment("maxScore")
    val score                 = PrefixMapping.segment("score")
    val uuid                  = PrefixMapping.segment("uuid")
    val label                 = PrefixMapping.segment("label")
    val path                  = PrefixMapping.segment("path")
    val grantTypes            = PrefixMapping.segment("grantTypes")
    val issuer                = PrefixMapping.segment("issuer")
    val keys                  = PrefixMapping.segment("keys")
    val authorizationEndpoint = PrefixMapping.segment("authorizationEndpoint")
    val tokenEndpoint         = PrefixMapping.segment("tokenEndpoint")
    val userInfoEndpoint      = PrefixMapping.segment("userInfoEndpoint")
    val revocationEndpoint    = PrefixMapping.segment("revocationEndpoint")
    val endSessionEndpoint    = PrefixMapping.segment("endSessionEndpoint")
    val instant               = PrefixMapping.segment("instant")
    val subject               = PrefixMapping.segment("subject")
    val reason                = PrefixMapping.segment("reason")

    //Resource types
    val Realm             = withSuffix("Realm")
    val Permissions       = withSuffix("Permissions")
    val AccessControlList = withSuffix("AccessControlList")

  }

  /**
    * Prefix mapping.
    *
    * @param prefix the prefix associated to this term, used in the Json-LD context
    * @param value  the fully expanded [[Uri]] to what the ''prefix'' resolves
    */
  final case class PrefixMapping private (prefix: String, value: Uri)

  object PrefixMapping {

    /**
      * Constructs a [[PrefixMapping]] vocabulary term from the given ''base'' and the provided ''lastSegment''.
      *
      * @param lastSegment the last segment to append to the ''base'' to build the metadata
      *                    vocabulary term
      */
    final def segment(lastSegment: String)(implicit base: Uri): PrefixMapping =
      PrefixMapping("_" + lastSegment, base.copy(path = base.path + lastSegment))

    implicit val prefixMappingShow: Show[PrefixMapping] = Show.show(_.value.toString)
  }
}
