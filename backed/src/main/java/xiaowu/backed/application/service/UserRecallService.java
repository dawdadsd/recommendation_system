package xiaowu.backed.application.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.backed.application.dto.RecallItemDetailDTO;
import xiaowu.backed.domain.item.entity.ItemCatalog;
import xiaowu.backed.domain.item.repository.ItemCatalogRepository;
import xiaowu.backed.domain.recommendation.repository.ModelVersionReadRepository;
import xiaowu.backed.domain.recommendation.repository.UserCfRecallRepository;
import xiaowu.backed.domain.recommendation.repository.UserItemPreferenceRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserRecallService {

  @Value("${recommendation.als.model-name:user-cf-als}")
  private String modelName;

  private final ModelVersionReadRepository modelVersionReadRepository;
  private final UserCfRecallRepository userCfRecallRepository;
  private final ItemCatalogRepository itemCatalogRepository;
  private final UserItemPreferenceRepository userItemPreferenceRepository;

  public List<RecallItemDetailDTO> recall(Long userId, int limit) {
    // 1. 取当前生效模型版本
    String modelVersion = modelVersionReadRepository.findActiveVersion(modelName)
        .orElseThrow(() -> new IllegalStateException("No active model version for " + modelName));

    // 2. 按 user_id + model_version 查 TopN 召回结果（已按 rank_position 排序）
    var recallList = userCfRecallRepository
        .findByUserIdAndModelVersionOrderByRankPositionAsc(userId, modelVersion);

    if (recallList.isEmpty()) {
      return List.of();
    }

    // 3. 批量查商品详情（查不到的视为已下架）
    List<Long> itemIds = recallList.stream()
        .map(r -> r.getItemId())
        .toList();

    Map<Long, ItemCatalog> catalogMap = itemCatalogRepository.findByItemIdIn(itemIds)
        .stream()
        .collect(Collectors.toMap(ItemCatalog::getItemId, item -> item));

    // 4. 过滤：已交互（模拟已购买）的商品
    Set<Long> interactedItemIds = userItemPreferenceRepository
        .findByUserIdOrderByPreferenceScoreDesc(userId)
        .stream()
        .map(p -> p.getItemId())
        .collect(Collectors.toSet());

    return recallList.stream()
        // 过滤已下架（不在商品表中）
        .filter(r -> catalogMap.containsKey(r.getItemId()))
        // 过滤已交互/已购买
        .filter(r -> !interactedItemIds.contains(r.getItemId()))
        // 补齐商品详情，组装 DTO
        .map(r -> {
          var item = catalogMap.get(r.getItemId());
          return new RecallItemDetailDTO(
              r.getItemId(),
              r.getRankPosition(),
              r.getScore(),
              item.getName(),
              item.getCategory(),
              item.getPrice(),
              item.getTags());
        })
        .limit(limit)
        .toList();
  }
}
