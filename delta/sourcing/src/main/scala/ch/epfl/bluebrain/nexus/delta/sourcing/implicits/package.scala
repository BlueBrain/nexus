package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.instances.OffsetInstances
import ch.epfl.bluebrain.nexus.delta.sourcing.syntax.{OffsetSyntax, ProjectionStreamSyntax}

package object implicits extends OffsetInstances with OffsetSyntax with ProjectionStreamSyntax
