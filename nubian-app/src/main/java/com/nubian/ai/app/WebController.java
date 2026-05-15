package com.nubian.ai.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController("appWebController")
@RequestMapping("/api/agent")
public final class WebController {

    private static final Logger log = LoggerFactory.getLogger(WebController.class);

    private final Operator operator;

    public WebController(Operator operator) {
        this.operator = operator;
    }

    public record TaskRequest(String prompt) {}
    public record TaskResponse(String runId) {}

    @PostMapping("/task")
    @ResponseBody
    public TaskResponse startTask(@RequestBody TaskRequest body) {
        if (body == null || body.prompt() == null || body.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        String runId = operator.startTask(body.prompt());
        log.info("[web] start task runId={} prompt={} chars", runId, body.prompt().length());
        return new TaskResponse(runId);
    }

    @GetMapping("/runs/{runId}/events")
    public SseEmitter events(@PathVariable("runId") String runId) {
        SseEmitter emitter = operator.subscribe(runId);
        if (emitter == null) {
            SseEmitter immediate = new SseEmitter(0L);
            try {
                immediate.send(SseEmitter.event().name("run-event")
                        .data(Events.of(runId, "task_failed", "unknown runId: " + runId, Map.of())));
                immediate.complete();
            } catch (Exception sendErr) {
                log.warn("[web] could not send unknown-runId notice: {}", sendErr.toString());
            }
            return immediate;
        }
        return emitter;
    }

    @PostMapping("/runs/{runId}/cancel")
    @ResponseBody
    public Operator.CancelResult cancel(@PathVariable("runId") String runId) {
        return operator.cancel(runId);
    }
}
