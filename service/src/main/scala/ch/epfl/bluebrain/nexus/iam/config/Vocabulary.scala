package ch.epfl.bluebrain.nexus.iam.config

import cats.Show
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node.IriNode
import ch.epfl.bluebrain.nexus.rdf.implicits._

/**
  * Constant vocabulary values
  */
object Vocabulary {

  /**
    * Nexus vocabulary.
    */
  object nxv {
    val base: Iri.AbsoluteIri                         = url"https://bluebrain.github.io/nexus/vocabulary/"
    implicit private[Vocabulary] val iriNode: IriNode = IriNode(base)

    /**
      * @param suffix the segment to suffix to the base
      * @return an [[AbsoluteIri]] composed by the ''base'' plus the provided ''suffix''
      */
    def withSuffix(suffix: String): AbsoluteIri = base + suffix

    // Metadata vocabulary
    val rev                   = PrefixMapping("rev")
    val deprecated            = PrefixMapping("deprecated")
    val createdAt             = PrefixMapping("createdAt")
    val updatedAt             = PrefixMapping("updatedAt")
    val createdBy             = PrefixMapping("createdBy")
    val updatedBy             = PrefixMapping("updatedBy")
    val constrainedBy         = PrefixMapping("constrainedBy")
    val self                  = PrefixMapping("self")
    val project               = PrefixMapping("project")
    val total                 = PrefixMapping("total")
    val results               = PrefixMapping("results")
    val maxScore              = PrefixMapping("maxScore")
    val score                 = PrefixMapping("score")
    val uuid                  = PrefixMapping("uuid")
    val label                 = PrefixMapping("label")
    val path                  = PrefixMapping("path")
    val grantTypes            = PrefixMapping("grantTypes")
    val issuer                = PrefixMapping("issuer")
    val keys                  = PrefixMapping("keys")
    val authorizationEndpoint = PrefixMapping("authorizationEndpoint")
    val tokenEndpoint         = PrefixMapping("tokenEndpoint")
    val userInfoEndpoint      = PrefixMapping("userInfoEndpoint")
    val revocationEndpoint    = PrefixMapping("revocationEndpoint")
    val endSessionEndpoint    = PrefixMapping("endSessionEndpoint")
    val instant               = PrefixMapping("instant")
    val subject               = PrefixMapping("subject")
    val reason                = PrefixMapping("reason")

    //Resource types
    val Realm             = withSuffix("Realm")
    val Permissions       = withSuffix("Permissions")
    val AccessControlList = withSuffix("AccessControlList")

  }

  /**
    * Prefix mapping.
    *
    * @param prefix the prefix associated to this term, used in the Json-LD context
    * @param value  the fully expanded [[AbsoluteIri]] to what the ''prefix'' resolves
    */
  final case class PrefixMapping(prefix: String, value: AbsoluteIri)

  object PrefixMapping {

    /**
      * Constructs a [[PrefixMapping]] vocabulary term from the given ''base'' and the provided ''lastSegment''.
      *
      * @param lastSegment the last segment to append to the ''base'' to build the metadata
      *                    vocabulary term
      */
    def apply(lastSegment: String)(implicit base: IriNode): PrefixMapping =
      PrefixMapping("_" + lastSegment, url"${base.value.show + lastSegment}")

    implicit def pmIriNode(m: PrefixMapping): IriNode         = IriNode(m.value)
    implicit def pmAbsoluteIri(m: PrefixMapping): AbsoluteIri = m.value
    implicit def pmIriF(p: PrefixMapping): IriNode => Boolean = _ == IriNode(p.value)
    implicit val pmShow: Show[PrefixMapping]                  = Show.show(_.value.show)
  }
}
