package io.github.samsonllam.weather.api;

import io.github.samsonllam.weather.domain.WeatherUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain failures to RFC 9457 problem details; Spring handles the generic cases such as a missing parameter. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(UnsupportedCityException.class)
    ProblemDetail unsupportedCity(UnsupportedCityException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Unsupported city");
        return problem;
    }

    /** Not logged here: the service logs each failed probe once, and the 503s in between are answered from memory. */
    @ExceptionHandler(WeatherUnavailableException.class)
    ProblemDetail weatherUnavailable(WeatherUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Every weather provider is unavailable and no earlier result is cached yet.");
        problem.setTitle("Weather unavailable");
        return problem;
    }
}
