package xiaowu.backed.interfaces.rest;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import xiaowu.backed.application.dto.RecallItemDetailDTO;
import xiaowu.backed.application.service.UserRecallService;

@RestController
@RequestMapping("/api/recommendation")
@RequiredArgsConstructor
public class RecallController {

  private final UserRecallService userRecallService;

  @GetMapping("/recall/{userId}")
  public List<RecallItemDetailDTO> recall(
      @PathVariable Long userId,
      @RequestParam(defaultValue = "20") int limit) {
    return userRecallService.recall(userId, limit);
  }
}
