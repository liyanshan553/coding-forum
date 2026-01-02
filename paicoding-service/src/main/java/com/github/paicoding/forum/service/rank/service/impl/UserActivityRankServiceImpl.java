package com.github.paicoding.forum.service.rank.service.impl;

import com.github.paicoding.forum.api.model.enums.rank.ActivityRankTimeEnum;
import com.github.paicoding.forum.api.model.vo.rank.dto.RankItemDTO;
import com.github.paicoding.forum.api.model.vo.user.dto.SimpleUserInfoDTO;
import com.github.paicoding.forum.core.cache.RedisClient;
import com.github.paicoding.forum.core.util.DateUtil;
import com.github.paicoding.forum.service.rank.service.UserActivityRankService;
import com.github.paicoding.forum.service.rank.service.model.ActivityScoreBo;
import com.github.paicoding.forum.service.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author YiHui
 * @date 2023/8/19
 */
@Slf4j
@Service
public class UserActivityRankServiceImpl implements UserActivityRankService {
    private static final String ACTIVITY_SCORE_KEY = "activity_rank_";
    private static final String ACTIVITY_SCORE_LUA = "local actionKey=KEYS[1];"
            + "local dayKey=KEYS[2];"
            + "local monthKey=KEYS[3];"
            + "local field=ARGV[1];"
            + "local score=tonumber(ARGV[2]);"
            + "local userId=ARGV[3];"
            + "local actionTtl=tonumber(ARGV[4]);"
            + "local dayTtl=tonumber(ARGV[5]);"
            + "local monthTtl=tonumber(ARGV[6]);"
            + "local ans=redis.call('HGET', actionKey, field);"
            + "if (not ans) then "
            + "  if (score > 0) then "
            + "    redis.call('HSET', actionKey, field, score);"
            + "    redis.call('EXPIRE', actionKey, actionTtl);"
            + "    redis.call('ZINCRBY', dayKey, score, userId);"
            + "    redis.call('ZINCRBY', monthKey, score, userId);"
            + "    if (redis.call('TTL', dayKey) < 0) then redis.call('EXPIRE', dayKey, dayTtl); end;"
            + "    if (redis.call('TTL', monthKey) < 0) then redis.call('EXPIRE', monthKey, monthTtl); end;"
            + "    return 1;"
            + "  end;"
            + "  return 0;"
            + "end;"
            + "if (tonumber(ans) > 0 and score < 0) then "
            + "  redis.call('HDEL', actionKey, field);"
            + "  redis.call('ZINCRBY', dayKey, score, userId);"
            + "  redis.call('ZINCRBY', monthKey, score, userId);"
            + "  return 1;"
            + "end;"
            + "return 0;";

    @Autowired
    private UserService userService;

    /**
     * 当天活跃度排行榜
     *
     * @return 当天排行榜key
     */
    private String todayRankKey() {
        return ACTIVITY_SCORE_KEY + DateUtil.format(DateTimeFormatter.ofPattern("yyyyMMdd"), System.currentTimeMillis());
    }

    /**
     * 本月排行榜
     *
     * @return 月度排行榜key
     */
    private String monthRankKey() {
        return ACTIVITY_SCORE_KEY + DateUtil.format(DateTimeFormatter.ofPattern("yyyyMM"), System.currentTimeMillis());
    }

    /**
     * 添加活跃分
     *
     * @param userId        用于更新活跃积分的用户
     * @param activityScore 触发活跃积分的时间类型
     */
    @Override
    public void addActivityScore(Long userId, ActivityScoreBo activityScore) {
        if (userId == null) {
            return;
        }

        // 1. 计算活跃度(正为加活跃,负为减活跃)
        String field;
        int score = 0;
        if (activityScore.getPath() != null) {
            field = "path_" + activityScore.getPath();
            score = 1;
        } else if (activityScore.getArticleId() != null) {
            field = activityScore.getArticleId() + "_";
            if (activityScore.getPraise() != null) {
                field += "praise";
                score = BooleanUtils.isTrue(activityScore.getPraise()) ? 2 : -2;
            } else if (activityScore.getCollect() != null) {
                field += "collect";
                score = BooleanUtils.isTrue(activityScore.getCollect()) ? 2 : -2;
            } else if (activityScore.getRate() != null) {
                // 评论回复
                field += "rate";
                score = BooleanUtils.isTrue(activityScore.getRate()) ? 3 : -3;
            } else if (BooleanUtils.isTrue(activityScore.getPublishArticle())) {
                // 发布文章
                field += "publish";
                score += 10;
            }
        } else if (activityScore.getFollowedUserId() != null) {
            // 关注添加积分
            field = activityScore.getFollowedUserId() + "_follow";
            score = BooleanUtils.isTrue(activityScore.getFollow()) ? 2 : -2;
        } else {
            return;
        }

        final String todayRankKey = todayRankKey();
        final String monthRankKey = monthRankKey();
        final String userActionKey = ACTIVITY_SCORE_KEY + userId + DateUtil.format(DateTimeFormatter.ofPattern("yyyyMMdd"), System.currentTimeMillis());
        Long updated = RedisClient.evalLong(ACTIVITY_SCORE_LUA,
                Arrays.asList(userActionKey, todayRankKey, monthRankKey),
                Arrays.asList(field, String.valueOf(score), String.valueOf(userId),
                        String.valueOf(31 * DateUtil.ONE_DAY_SECONDS),
                        String.valueOf(31 * DateUtil.ONE_DAY_SECONDS),
                        String.valueOf(12 * DateUtil.ONE_MONTH_SECONDS)));
        if (updated != null && updated == 1L && log.isDebugEnabled()) {
            log.info("活跃度更新完成! key#field = {}#{}, add = {}", todayRankKey, userId, score);
        }
    }

    @Override
    public RankItemDTO queryRankInfo(Long userId, ActivityRankTimeEnum time) {
        RankItemDTO item = new RankItemDTO();
        item.setUser(userService.querySimpleUserInfo(userId));

        String rankKey = time == ActivityRankTimeEnum.DAY ? todayRankKey() : monthRankKey();
        ImmutablePair<Integer, Double> rank = RedisClient.zRankInfo(rankKey, String.valueOf(userId));
        item.setRank(rank.getLeft());
        item.setScore(rank.getRight().intValue());
        return item;
    }

    @Override
    public List<RankItemDTO> queryRankList(ActivityRankTimeEnum time, int size) {
        String rankKey = time == ActivityRankTimeEnum.DAY ? todayRankKey() : monthRankKey();
        // 1. 获取topN的活跃用户
        List<ImmutablePair<String, Double>> rankList = RedisClient.zTopNScore(rankKey, size);
        if (CollectionUtils.isEmpty(rankList)) {
            return Collections.emptyList();
        }

        // 2. 查询用户对应的基本信息
        // 构建userId -> 活跃评分的map映射，用于补齐用户信息
        Map<Long, Integer> userScoreMap = rankList.stream().collect(Collectors.toMap(s -> Long.valueOf(s.getLeft()), s -> s.getRight().intValue()));
        List<SimpleUserInfoDTO> users = userService.batchQuerySimpleUserInfo(userScoreMap.keySet());

        // 3. 根据评分进行排序
        List<RankItemDTO> rank = users.stream()
                .map(user -> new RankItemDTO().setUser(user).setScore(userScoreMap.getOrDefault(user.getUserId(), 0)))
                .sorted((o1, o2) -> Integer.compare(o2.getScore(), o1.getScore()))
                .collect(Collectors.toList());

        // 4. 补齐每个用户的排名
        IntStream.range(0, rank.size()).forEach(i -> rank.get(i).setRank(i + 1));
        return rank;
    }
}
