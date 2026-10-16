package okay.spring;

import org.springframework.boot.test.context.SpringBootTest;

/** {@link EffControllerTest}'s controller and checks, in a reactive application on Netty */
@SpringBootTest(classes = EffControllerTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.main.web-application-type=reactive")
class WebFluxTest extends EffControllerTest {}
