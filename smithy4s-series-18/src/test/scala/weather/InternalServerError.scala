package weather

import smithy4s.Hints
import smithy4s.Schema
import smithy4s.ShapeId
import smithy4s.ShapeTag
import smithy4s.Smithy4sThrowable
import smithy4s.schema.Schema.string
import smithy4s.schema.Schema.struct

final case class InternalServerError(reason: Option[String] = None) extends Smithy4sThrowable

object InternalServerError extends ShapeTag.Companion[InternalServerError] {
  val id: ShapeId = ShapeId("weather", "InternalServerError")

  val hints: Hints = Hints(
    smithy.api.Error.SERVER.widen,
    smithy.api.HttpError(500),
  ).lazily

  // constructor using the original order from the spec
  private def make(reason: Option[String]): InternalServerError = InternalServerError(reason)

  implicit val schema: Schema[InternalServerError] = struct(
    string.optional[InternalServerError]("reason", _.reason),
  )(make).withId(id).addHints(hints)
}
