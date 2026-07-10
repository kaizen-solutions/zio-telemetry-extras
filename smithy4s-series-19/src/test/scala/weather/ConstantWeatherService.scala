package weather

import weather.{
  CityId,
  CreateCityOutput,
  GetWeatherOutput,
  InternalServerError,
  WeatherService,
  WeatherServiceOperation
}
import zio.*

object ConstantWeatherService extends WeatherService.ErrorAware[IO] {

  override def getWeather(
      cityId: CityId,
      region: String
  ): IO[WeatherServiceOperation.GetWeatherError, GetWeatherOutput] =
    ZIO.succeed(GetWeatherOutput("weather", degrees = Option(100)))

  override def createCity(
      city: String,
      country: String
  ): IO[WeatherServiceOperation.CreateCityError, CreateCityOutput] =
    ZIO.fail(WeatherServiceOperation.CreateCityError.internalServerError(InternalServerError()))

}
