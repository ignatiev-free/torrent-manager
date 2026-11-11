package app.torrentmanager.report;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportPageController {
    @GetMapping(value = "/reports", produces = MediaType.TEXT_HTML_VALUE)
    public Resource page() {
        return new ClassPathResource("static/reports.html");
    }
}
