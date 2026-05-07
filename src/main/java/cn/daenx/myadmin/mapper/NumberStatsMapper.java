package cn.daenx.myadmin.mapper;

import cn.daenx.myadmin.entity.NumberStats;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;


import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface NumberStatsMapper extends BaseMapper<NumberStats> {

    @Insert("INSERT INTO t_number_stats " +
            "(lottery_category, game_type, play_type, issue_key, number_zone, number, order_count, sum_amount) " +
            "VALUES (#{lotteryCategory}, #{gameType}, #{playType}, #{issueKey}, #{numberZone}, #{number}, #{betCount}, #{amount}) " +
            "ON DUPLICATE KEY UPDATE " +
            "order_count = order_count + #{betCount}, " +
            "sum_amount = sum_amount + VALUES(sum_amount), " +
            "last_updated_at = NOW(3)")
    int upsertStats(@Param("lotteryCategory") String lotteryCategory,
                    @Param("gameType") String gameType,
                    @Param("playType") String playType,
                    @Param("issueKey") String issueKey,
                    @Param("numberZone") String numberZone,
                    @Param("number") String number,
                    @Param("betCount") int betCount,
                    @Param("amount") BigDecimal amount);

    @Insert("<script>" +
            "INSERT INTO t_number_stats " +
            "(lottery_category, game_type, play_type, issue_key, number_zone, number, order_count, sum_amount) VALUES " +
            "<foreach collection='stats' item='stat' separator=','>" +
            "(#{stat.lotteryCategory}, #{stat.gameType}, #{stat.playType}, #{stat.issueKey}, " +
            "#{stat.numberZone}, #{stat.number}, #{stat.orderCount}, #{stat.sumAmount})" +
            "</foreach> " +
            "ON DUPLICATE KEY UPDATE " +
            "order_count = order_count + VALUES(order_count), " +
            "sum_amount = sum_amount + VALUES(sum_amount), " +
            "last_updated_at = NOW(3)" +
            "</script>")
    int upsertStatsBatch(@Param("stats") List<NumberStats> stats);

    @Select("<script>" +
            "SELECT number_zone, number, SUM(order_count) AS order_count, SUM(sum_amount) AS sum_amount " +
            "FROM t_number_stats " +
            "WHERE lottery_category = #{lotteryCategory} " +
            "AND game_type = #{gameType} " +
            "AND issue_key = #{issueKey} " +
            "<if test='playType != null'> AND play_type = #{playType} </if>" +
            "<if test='numberZone != null'> AND number_zone = #{numberZone} </if>" +
            "<if test='number != null'> AND number = #{number} </if>" +
            "GROUP BY number_zone, number " +
            "ORDER BY ${sortColumn} ${sortDirection}, number ASC " +
            "LIMIT #{offset}, #{pageSize}" +
            "</script>")
    List<NumberStats> queryPage(@Param("lotteryCategory") String lotteryCategory,
                                @Param("gameType") String gameType,
                                @Param("issueKey") String issueKey,
                                @Param("playType") String playType,
                                @Param("numberZone") String numberZone,
                                @Param("number") String number,
                                @Param("sortColumn") String sortColumn,
                                @Param("sortDirection") String sortDirection,
                                @Param("offset") int offset,
                                @Param("pageSize") int pageSize);

    @Select("<script>" +
            "SELECT COUNT(*) FROM (" +
            "SELECT 1 FROM t_number_stats " +
            "WHERE lottery_category = #{lotteryCategory} " +
            "AND game_type = #{gameType} " +
            "AND issue_key = #{issueKey} " +
            "<if test='playType != null'> AND play_type = #{playType} </if>" +
            "<if test='numberZone != null'> AND number_zone = #{numberZone} </if>" +
            "<if test='number != null'> AND number = #{number} </if>" +
            "GROUP BY number_zone, number" +
            ") grouped_stats" +
            "</script>")
    int countGrouped(@Param("lotteryCategory") String lotteryCategory,
                     @Param("gameType") String gameType,
                     @Param("issueKey") String issueKey,
                     @Param("playType") String playType,
                     @Param("numberZone") String numberZone,
                     @Param("number") String number);

    @Select("<script>" +
            "SELECT IFNULL(SUM(order_count),0) AS order_count, IFNULL(SUM(sum_amount),0) AS sum_amount " +
            "FROM t_number_stats " +
            "WHERE lottery_category = #{lotteryCategory} " +
            "AND game_type = #{gameType} " +
            "AND issue_key = #{issueKey} " +
            "<if test='playType != null'> AND play_type = #{playType} </if>" +
            "<if test='numberZone != null'> AND number_zone = #{numberZone} </if>" +
            "<if test='number != null'> AND number = #{number} </if>" +
            "</script>")
    NumberStats queryTotals(@Param("lotteryCategory") String lotteryCategory,
                            @Param("gameType") String gameType,
                            @Param("issueKey") String issueKey,
                            @Param("playType") String playType,
                            @Param("numberZone") String numberZone,
                            @Param("number") String number);

    @Delete("DELETE FROM t_number_stats WHERE issue_key = #{issueKey}")
    int deleteByIssueKey(@Param("issueKey") String issueKey);

    @Select("SELECT IFNULL(SUM(order_count),0) AS order_count, IFNULL(SUM(sum_amount),0) AS sum_amount " +
            "FROM t_number_stats WHERE issue_key = #{issueKey}")
    NumberStats queryIssueTotals(@Param("issueKey") String issueKey);

    @Select("SELECT IFNULL(SUM(i.bet_count),0) AS order_count, IFNULL(SUM(n.amount_alloc),0) AS sum_amount " +
            "FROM t_order_item i " +
            "JOIN t_order_item_number n ON n.item_id = i.id " +
            "JOIN t_order_parse_batch b ON b.id = i.batch_id AND b.raw_id = i.raw_id " +
            "WHERE i.issue_key = #{issueKey} AND b.is_effective = 1 AND b.parse_status = 1")
    NumberStats queryDetailNumberTotals(@Param("issueKey") String issueKey);

    @Select("SELECT COUNT(*) FROM (" +
            "  SELECT i.lottery_category, i.game_type, i.play_type, i.issue_key, n.number_zone, n.number, " +
            "         SUM(i.bet_count) AS order_count, SUM(n.amount_alloc) AS sum_amount " +
            "  FROM t_order_item i " +
            "  JOIN t_order_item_number n ON n.item_id = i.id " +
            "  JOIN t_order_parse_batch b ON b.id = i.batch_id AND b.raw_id = i.raw_id " +
            "  WHERE i.issue_key = #{issueKey} AND b.is_effective = 1 AND b.parse_status = 1 " +
            "  GROUP BY i.lottery_category, i.game_type, i.play_type, i.issue_key, n.number_zone, n.number" +
            ") d LEFT JOIN t_number_stats s ON s.issue_key = d.issue_key " +
            " AND s.lottery_category = d.lottery_category AND s.game_type = d.game_type " +
            " AND s.play_type <=> d.play_type AND s.number_zone <=> d.number_zone AND s.number = d.number " +
            "WHERE s.id IS NULL OR s.order_count <> d.order_count OR ABS(s.sum_amount - d.sum_amount) > 0.01")
    int countMissingOrChangedStatsFromDetails(@Param("issueKey") String issueKey);

    @Select("SELECT COUNT(*) FROM t_number_stats s LEFT JOIN (" +
            "  SELECT i.lottery_category, i.game_type, i.play_type, i.issue_key, n.number_zone, n.number " +
            "  FROM t_order_item i " +
            "  JOIN t_order_item_number n ON n.item_id = i.id " +
            "  JOIN t_order_parse_batch b ON b.id = i.batch_id AND b.raw_id = i.raw_id " +
            "  WHERE i.issue_key = #{issueKey} AND b.is_effective = 1 AND b.parse_status = 1 " +
            "  GROUP BY i.lottery_category, i.game_type, i.play_type, i.issue_key, n.number_zone, n.number" +
            ") d ON d.issue_key = s.issue_key " +
            " AND d.lottery_category = s.lottery_category AND d.game_type = s.game_type " +
            " AND d.play_type <=> s.play_type AND d.number_zone <=> s.number_zone AND d.number = s.number " +
            "WHERE s.issue_key = #{issueKey} AND d.issue_key IS NULL")
    int countOrphanStatsWithoutDetails(@Param("issueKey") String issueKey);

    @Select("SELECT lottery_category, game_type, IFNULL(play_type, 'ALL') AS play_type, " +
            "SUM(order_count) AS order_count, SUM(sum_amount) AS sum_amount " +
            "FROM t_number_stats WHERE issue_key = #{issueKey} " +
            "GROUP BY lottery_category, game_type, IFNULL(play_type, 'ALL') " +
            "ORDER BY lottery_category, game_type, play_type")
    List<Map<String, Object>> queryStatsTotalsByGame(@Param("issueKey") String issueKey);

    @Insert("INSERT INTO t_number_stats " +
            "(lottery_category, game_type, play_type, issue_key, number_zone, number, order_count, sum_amount) " +
            "SELECT i.lottery_category, i.game_type, i.play_type, i.issue_key, n.number_zone, n.number, " +
            "       SUM(i.bet_count) AS order_count, SUM(n.amount_alloc) AS sum_amount " +
            "FROM t_order_item i " +
            "JOIN t_order_item_number n ON n.item_id = i.id " +
            "JOIN t_order_parse_batch b ON b.id = i.batch_id AND b.raw_id = i.raw_id " +
            "WHERE i.issue_key = #{issueKey} AND b.is_effective = 1 AND b.parse_status = 1 " +
            "GROUP BY i.lottery_category, i.game_type, i.play_type, i.issue_key, n.number_zone, n.number")
    int rebuildIssueFromItems(@Param("issueKey") String issueKey);

    @Update("UPDATE t_number_stats SET order_count = order_count - #{betCount}, sum_amount = sum_amount - #{amount} " +
            "WHERE lottery_category = #{lotteryCategory} AND game_type = #{gameType} AND play_type = #{playType} " +
            "AND issue_key = #{issueKey} AND number_zone = #{numberZone} AND number = #{number}")
    int decrementStats(@Param("lotteryCategory") String lotteryCategory,
                       @Param("gameType") String gameType,
                       @Param("playType") String playType,
                       @Param("issueKey") String issueKey,
                       @Param("numberZone") String numberZone,
                       @Param("number") String number,
                       @Param("betCount") int betCount,
                       @Param("amount") BigDecimal amount);

    @Delete("DELETE FROM t_number_stats WHERE order_count <= 0 OR sum_amount <= 0")
    int deleteNonPositiveRows();
}
