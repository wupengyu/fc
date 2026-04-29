package cn.daenx.myadmin.mapper;

import cn.daenx.myadmin.entity.NumberStats;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;


import java.math.BigDecimal;
import java.util.List;

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
