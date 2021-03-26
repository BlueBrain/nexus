package ch.epfl.bluebrain.nexus.delta.sdk.views.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId

/**
 * Case class describing the needed metadata to index a view
 *
 * @param projectRef the project of the view
 * @param id         the identifier of the view
 * @param index      the name of the destination index
 * @param rev        the revision of the view
 * @param deprecated if the view has been deprecated
 * @param value      the view value
 */
final case class ViewIndex[+V](
                                projectRef: ProjectRef,
                                id: Iri,
                                projectionId: ViewProjectionId,
                                index: String,
                                rev: Long,
                                deprecated: Boolean,
                                value: V
)
