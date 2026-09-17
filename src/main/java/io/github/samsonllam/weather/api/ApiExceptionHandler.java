package io.github.samsonllam.weather.api;

import io.github.samsonllam.weather.domain.WeatherUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain failures to RFC 9457 problem details; Spring handles the generic cases such as a missing parameter. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(UnsupportedCityException.class)
    ProblemDetail unsupportedCity(UnsupportedCityException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Unsupported city");
        return problem;
    }

    @ExceptionHandler(WeatherUnavailableException.class)
    ProblemDetail weatherUnavailable(WeatherUnavailableException e) {
        log.warn("{}: {}", e.getMessage(), e.getCause() == null ? "" : e.getCause().getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Every weather provider is unavailable and no earlier result is cached yet.");
        problem.setTitle("Weather unavailable");
        return problem;
    }
}
