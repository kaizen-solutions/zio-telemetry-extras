package weather

import smithy4s.{Endpoint, Hints, Schema, Service, ShapeId, Transformation}
import smithy4s.kinds.toPolyFunction5.const5
import smithy4s.kinds.PolyFunction5
import smithy4s.schema.{ErrorSchema, OperationSchema}
import smithy4s.schema.Schema.{bijection, union}

trait WeatherServiceGen[F[_, _, _, _, _]] {
  self =>

  /** Get the weather for a city
    *
    * HTTP GET /cities/{cityId}/weather
    */
  def getWeather(
      cityId: CityId,
      region: String
  ): F[GetWeatherInput, WeatherServiceOperation.GetWeatherError, GetWeatherOutput, Nothing, Nothing]

  /** HTTP POST /cities */
  def createCity(
      city: String,
      country: String
  ): F[CreateCityInput, WeatherServiceOperation.CreateCityError, CreateCityOutput, Nothing, Nothing]

  final def transform: Transformation.PartiallyApplied[WeatherServiceGen[F]] =
    Transformation.of[WeatherServiceGen[F]](this)
}

object WeatherServiceGen extends Service.Mixin[WeatherServiceGen, WeatherServiceOperation] {

  val id: ShapeId     = ShapeId("weather", "WeatherService")
  val version: String = ""

  val hints: Hints = Hints(
    alloy.SimpleRestJson()
  ).lazily

  def apply[F[_]](implicit F: Impl[F]): F.type = F

  object ErrorAware {
    def apply[F[_, _]](implicit F: ErrorAware[F]): F.type = F
    type Default[F[+_, +_]] = Constant[smithy4s.kinds.stubs.Kind2[F]#toKind5]
  }

  val endpoints: Vector[smithy4s.Endpoint[WeatherServiceOperation, ?, ?, ?, ?, ?]] = Vector(
    WeatherServiceOperation.GetWeather,
    WeatherServiceOperation.CreateCity
  )

  def input[I, E, O, SI, SO](op: WeatherServiceOperation[I, E, O, SI, SO]): I          = op.input
  def ordinal[I, E, O, SI, SO](op: WeatherServiceOperation[I, E, O, SI, SO]): Int      = op.ordinal
  override def endpoint[I, E, O, SI, SO](op: WeatherServiceOperation[I, E, O, SI, SO]) = op.endpoint
  class Constant[P[-_, +_, +_, +_, +_]](value: P[Any, Nothing, Nothing, Nothing, Nothing])
      extends WeatherServiceOperation.Transformed[WeatherServiceOperation, P](
        reified,
        const5(value)
      )
  type Default[F[+_]] = Constant[smithy4s.kinds.stubs.Kind1[F]#toKind5]
  def reified: WeatherServiceGen[WeatherServiceOperation] = WeatherServiceOperation.reified
  def mapK5[P[_, _, _, _, _], P1[_, _, _, _, _]](
      alg: WeatherServiceGen[P],
      f: PolyFunction5[P, P1]
  ): WeatherServiceGen[P1] = new WeatherServiceOperation.Transformed(alg, f)
  def fromPolyFunction[P[_, _, _, _, _]](
      f: PolyFunction5[WeatherServiceOperation, P]
  ): WeatherServiceGen[P] = new WeatherServiceOperation.Transformed(reified, f)
  def toPolyFunction[P[_, _, _, _, _]](
      impl: WeatherServiceGen[P]
  ): PolyFunction5[WeatherServiceOperation, P] = WeatherServiceOperation.toPolyFunction(impl)

  type GetWeatherError = WeatherServiceOperation.GetWeatherError
  val GetWeatherError = WeatherServiceOperation.GetWeatherError
  type CreateCityError = WeatherServiceOperation.CreateCityError
  val CreateCityError = WeatherServiceOperation.CreateCityError
}

sealed trait WeatherServiceOperation[Input, Err, Output, StreamedInput, StreamedOutput] {
  def run[F[_, _, _, _, _]](
      impl: WeatherServiceGen[F]
  ): F[Input, Err, Output, StreamedInput, StreamedOutput]
  def ordinal: Int
  def input: Input
  def endpoint: Endpoint[WeatherServiceOperation, Input, Err, Output, StreamedInput, StreamedOutput]
}

object WeatherServiceOperation {

  object reified extends WeatherServiceGen[WeatherServiceOperation] {
    def getWeather(cityId: CityId, region: String): GetWeather = GetWeather(
      GetWeatherInput(cityId, region)
    )
    def createCity(city: String, country: String): CreateCity = CreateCity(
      CreateCityInput(city, country)
    )
  }
  class Transformed[P[_, _, _, _, _], P1[_, _, _, _, _]](
      alg: WeatherServiceGen[P],
      f: PolyFunction5[P, P1]
  ) extends WeatherServiceGen[P1] {
    def getWeather(cityId: CityId, region: String): P1[
      GetWeatherInput,
      WeatherServiceOperation.GetWeatherError,
      GetWeatherOutput,
      Nothing,
      Nothing
    ] = f[
      GetWeatherInput,
      WeatherServiceOperation.GetWeatherError,
      GetWeatherOutput,
      Nothing,
      Nothing
    ](alg.getWeather(cityId, region))
    def createCity(city: String, country: String): P1[
      CreateCityInput,
      WeatherServiceOperation.CreateCityError,
      CreateCityOutput,
      Nothing,
      Nothing
    ] = f[
      CreateCityInput,
      WeatherServiceOperation.CreateCityError,
      CreateCityOutput,
      Nothing,
      Nothing
    ](alg.createCity(city, country))
  }

  def toPolyFunction[P[_, _, _, _, _]](
      impl: WeatherServiceGen[P]
  ): PolyFunction5[WeatherServiceOperation, P] = new PolyFunction5[WeatherServiceOperation, P] {
    def apply[I, E, O, SI, SO](op: WeatherServiceOperation[I, E, O, SI, SO]): P[I, E, O, SI, SO] =
      op.run(impl)
  }
  final case class GetWeather(input: GetWeatherInput)
      extends WeatherServiceOperation[
        GetWeatherInput,
        WeatherServiceOperation.GetWeatherError,
        GetWeatherOutput,
        Nothing,
        Nothing
      ] {
    def run[F[_, _, _, _, _]](impl: WeatherServiceGen[F]): F[
      GetWeatherInput,
      WeatherServiceOperation.GetWeatherError,
      GetWeatherOutput,
      Nothing,
      Nothing
    ]                = impl.getWeather(input.cityId, input.region)
    def ordinal: Int = 0
    def endpoint: smithy4s.Endpoint[
      WeatherServiceOperation,
      GetWeatherInput,
      WeatherServiceOperation.GetWeatherError,
      GetWeatherOutput,
      Nothing,
      Nothing
    ] = GetWeather
  }
  object GetWeather
      extends smithy4s.Endpoint[
        WeatherServiceOperation,
        GetWeatherInput,
        WeatherServiceOperation.GetWeatherError,
        GetWeatherOutput,
        Nothing,
        Nothing
      ] {
    val schema: OperationSchema[
      GetWeatherInput,
      WeatherServiceOperation.GetWeatherError,
      GetWeatherOutput,
      Nothing,
      Nothing
    ] = Schema
      .operation(ShapeId("weather", "GetWeather"))
      .withInput(GetWeatherInput.schema)
      .withError(GetWeatherError.errorSchema)
      .withOutput(GetWeatherOutput.schema)
      .withHints(
        smithy.api.Documentation("Get the weather for a city"),
        smithy.api.Examples(
          List(
            smithy.api.Example(
              title = "Get the weather for a city",
              documentation = None,
              input = Some(
                smithy4s.Document.obj(
                  "cityId" -> smithy4s.Document.fromString("1"),
                  "region" -> smithy4s.Document.fromString("Europe")
                )
              ),
              output = Some(
                smithy4s.Document.obj(
                  "weather" -> smithy4s.Document.fromString("sunny"),
                  "degrees" -> smithy4s.Document.fromDouble(25.0d)
                )
              ),
              error = None,
              allowConstraintErrors = None
            )
          )
        ),
        smithy.api.Http(
          method = smithy.api.NonEmptyString("GET"),
          uri = smithy.api.NonEmptyString("/cities/{cityId}/weather"),
          code = 200
        ),
        smithy.api.Readonly()
      )
    def wrap(input: GetWeatherInput): GetWeather = GetWeather(input)
  }
  sealed trait GetWeatherError extends scala.Product with scala.Serializable { self =>
    @inline final def widen: GetWeatherError = this
    def $ordinal: Int

    object project {
      def internalServerError: Option[InternalServerError] =
        GetWeatherError.InternalServerErrorCase.alt.project.lift(self).map(_.internalServerError)
      def cityNotFoundError: Option[CityNotFoundError] =
        GetWeatherError.CityNotFoundErrorCase.alt.project.lift(self).map(_.cityNotFoundError)
    }

    def accept[A](visitor: GetWeatherError.Visitor[A]): A = this match {
      case value: GetWeatherError.InternalServerErrorCase =>
        visitor.internalServerError(value.internalServerError)
      case value: GetWeatherError.CityNotFoundErrorCase =>
        visitor.cityNotFoundError(value.cityNotFoundError)
    }
  }
  object GetWeatherError extends ErrorSchema.Companion[GetWeatherError] {

    def internalServerError(internalServerError: InternalServerError): GetWeatherError =
      InternalServerErrorCase(internalServerError)
    def cityNotFoundError(cityNotFoundError: CityNotFoundError): GetWeatherError =
      CityNotFoundErrorCase(cityNotFoundError)

    val id: ShapeId = ShapeId("weather", "GetWeatherError")

    val hints: Hints = Hints.empty

    final case class InternalServerErrorCase(internalServerError: InternalServerError)
        extends GetWeatherError { final def $ordinal: Int = 0 }
    final case class CityNotFoundErrorCase(cityNotFoundError: CityNotFoundError)
        extends GetWeatherError { final def $ordinal: Int = 1 }

    object InternalServerErrorCase {
      val hints: Hints                                            = Hints.empty
      val schema: Schema[GetWeatherError.InternalServerErrorCase] = bijection(
        InternalServerError.schema.addHints(hints),
        GetWeatherError.InternalServerErrorCase(_),
        _.internalServerError
      )
      val alt = schema.oneOf[GetWeatherError]("InternalServerError")
    }
    object CityNotFoundErrorCase {
      val hints: Hints                                          = Hints.empty
      val schema: Schema[GetWeatherError.CityNotFoundErrorCase] = bijection(
        CityNotFoundError.schema.addHints(hints),
        GetWeatherError.CityNotFoundErrorCase(_),
        _.cityNotFoundError
      )
      val alt = schema.oneOf[GetWeatherError]("CityNotFoundError")
    }

    trait Visitor[A] {
      def internalServerError(value: InternalServerError): A
      def cityNotFoundError(value: CityNotFoundError): A
    }

    object Visitor {
      trait Default[A] extends Visitor[A] {
        def default: A
        def internalServerError(value: InternalServerError): A = default
        def cityNotFoundError(value: CityNotFoundError): A     = default
      }
    }

    implicit val schema: Schema[GetWeatherError] = union(
      GetWeatherError.InternalServerErrorCase.alt,
      GetWeatherError.CityNotFoundErrorCase.alt
    ) {
      _.$ordinal
    }
    def liftError(throwable: Throwable): Option[GetWeatherError] = throwable match {
      case e: InternalServerError => Some(GetWeatherError.InternalServerErrorCase(e))
      case e: CityNotFoundError   => Some(GetWeatherError.CityNotFoundErrorCase(e))
      case _                      => None
    }
    def unliftError(e: GetWeatherError): Throwable = e match {
      case GetWeatherError.InternalServerErrorCase(e) => e
      case GetWeatherError.CityNotFoundErrorCase(e)   => e
    }
  }
  final case class CreateCity(input: CreateCityInput)
      extends WeatherServiceOperation[
        CreateCityInput,
        WeatherServiceOperation.CreateCityError,
        CreateCityOutput,
        Nothing,
        Nothing
      ] {
    def run[F[_, _, _, _, _]](impl: WeatherServiceGen[F]): F[
      CreateCityInput,
      WeatherServiceOperation.CreateCityError,
      CreateCityOutput,
      Nothing,
      Nothing
    ]                = impl.createCity(input.city, input.country)
    def ordinal: Int = 1
    def endpoint: smithy4s.Endpoint[
      WeatherServiceOperation,
      CreateCityInput,
      WeatherServiceOperation.CreateCityError,
      CreateCityOutput,
      Nothing,
      Nothing
    ] = CreateCity
  }
  object CreateCity
      extends smithy4s.Endpoint[
        WeatherServiceOperation,
        CreateCityInput,
        WeatherServiceOperation.CreateCityError,
        CreateCityOutput,
        Nothing,
        Nothing
      ] {
    val schema: OperationSchema[
      CreateCityInput,
      WeatherServiceOperation.CreateCityError,
      CreateCityOutput,
      Nothing,
      Nothing
    ] = Schema
      .operation(ShapeId("weather", "CreateCity"))
      .withInput(CreateCityInput.schema)
      .withError(CreateCityError.errorSchema)
      .withOutput(CreateCityOutput.schema)
      .withHints(
        smithy.api.Http(
          method = smithy.api.NonEmptyString("POST"),
          uri = smithy.api.NonEmptyString("/cities"),
          code = 201
        )
      )
    def wrap(input: CreateCityInput): CreateCity = CreateCity(input)
  }
  sealed trait CreateCityError extends scala.Product with scala.Serializable { self =>
    @inline final def widen: CreateCityError = this
    def $ordinal: Int

    object project {
      def internalServerError: Option[InternalServerError] =
        CreateCityError.InternalServerErrorCase.alt.project.lift(self).map(_.internalServerError)
    }

    def accept[A](visitor: CreateCityError.Visitor[A]): A = this match {
      case value: CreateCityError.InternalServerErrorCase =>
        visitor.internalServerError(value.internalServerError)
    }
  }
  object CreateCityError extends ErrorSchema.Companion[CreateCityError] {

    def internalServerError(internalServerError: InternalServerError): CreateCityError =
      InternalServerErrorCase(internalServerError)

    val id: ShapeId = ShapeId("weather", "CreateCityError")

    val hints: Hints = Hints.empty

    final case class InternalServerErrorCase(internalServerError: InternalServerError)
        extends CreateCityError { final def $ordinal: Int = 0 }

    object InternalServerErrorCase {
      val hints: Hints                                            = Hints.empty
      val schema: Schema[CreateCityError.InternalServerErrorCase] = bijection(
        InternalServerError.schema.addHints(hints),
        CreateCityError.InternalServerErrorCase(_),
        _.internalServerError
      )
      val alt = schema.oneOf[CreateCityError]("InternalServerError")
    }

    trait Visitor[A] {
      def internalServerError(value: InternalServerError): A
    }

    object Visitor {
      trait Default[A] extends Visitor[A] {
        def default: A
        def internalServerError(value: InternalServerError): A = default
      }
    }

    implicit val schema: Schema[CreateCityError] = union(
      CreateCityError.InternalServerErrorCase.alt
    ) {
      _.$ordinal
    }
    def liftError(throwable: Throwable): Option[CreateCityError] = throwable match {
      case e: InternalServerError => Some(CreateCityError.InternalServerErrorCase(e))
      case _                      => None
    }
    def unliftError(e: CreateCityError): Throwable = e match {
      case CreateCityError.InternalServerErrorCase(e) => e
    }
  }
}
