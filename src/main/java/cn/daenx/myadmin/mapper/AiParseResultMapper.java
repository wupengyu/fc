package cn.daenx.myadmin.mapper;

import cn.daenx.myadmin.entity.AiParseResultRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiParseResultMapper extends BaseMapper<AiParseResultRecord> {

    @Insert("<script>" +
            "INSERT INTO t_ai_parse_result " +
            "(raw_id, batch_id, item_index, valid, status, reason, category, game, play, zone, " +
            "numbers, bet, multiple, amount, issue_key, created_at) VALUES " +
            "<foreach collection='records' item='record' separator=','>" +
            "(#{record.rawId}, #{record.batchId}, #{record.itemIndex}, #{record.valid}, #{record.status}, " +
            "#{record.reason}, #{record.category}, #{record.game}, #{record.play}, #{record.zone}, " +
            "#{record.numbers}, #{record.bet}, #{record.multiple}, #{record.amount}, #{record.issueKey}, NOW(3))" +
            "</foreach>" +
            "</script>")
    int insertBatchRecords(@Param("records") List<AiParseResultRecord> records);

    @Delete("DELETE FROM t_ai_parse_result WHERE issue_key = #{issueKey}")
    int deleteByIssueKey(@Param("issueKey") String issueKey);

    @Delete("<script>" +
            "DELETE FROM t_ai_parse_result WHERE raw_id IN " +
            "<foreach collection='rawIds' item='rawId' open='(' separator=',' close=')'>" +
            "   #{rawId}" +
            "</foreach>" +
            "</script>")
    int deleteByRawIds(@Param("rawIds") List<Long> rawIds);

    /**
     * 查询指定期次、彩种下最新有效批次的成功解析记录
     */
    @Select("<script>" +
            "SELECT r.* " +
            "FROM t_ai_parse_result r " +
            "INNER JOIN (" +
            "   SELECT raw_id, MAX(batch_id) AS latest_batch_id " +
            "   FROM t_ai_parse_result " +
            "   WHERE issue_key = #{issueKey} " +
            "   AND category = #{category} " +
            "   AND game = #{game} " +
            "   AND status = 'SUCCESS' " +
            "   AND valid = 1 " +
            "   GROUP BY raw_id" +
            ") latest ON latest.raw_id = r.raw_id AND latest.latest_batch_id = r.batch_id " +
            "WHERE r.issue_key = #{issueKey} " +
            "AND r.category = #{category} " +
            "AND r.game = #{game} " +
            "AND r.status = 'SUCCESS' " +
            "AND r.valid = 1 " +
            "<if test='playType != null'> AND r.play = #{playType} </if>" +
            "<if test='numberZone != null'> AND r.zone = #{numberZone} </if>" +
            "<if test='betNumber != null'> AND r.numbers LIKE CONCAT('%\\\"', #{betNumber}, '\\\"%') </if>" +
            "</script>")
    List<AiParseResultRecord> queryLatestSuccessByIssue(@Param("issueKey") String issueKey,
                                                        @Param("category") String category,
                                                        @Param("game") String game,
                                                        @Param("playType") String playType,
                                                        @Param("numberZone") String numberZone,
                                                        @Param("betNumber") String betNumber);

    /**
     * 查询指定期次下每条原始消息最新有效批次的全部成功解析记录
     */
    @Select("SELECT r.* " +
            "FROM t_ai_parse_result r " +
            "INNER JOIN (" +
            "   SELECT raw_id, MAX(batch_id) AS latest_batch_id " +
            "   FROM t_ai_parse_result " +
            "   WHERE issue_key = #{issueKey} " +
            "   AND status = 'SUCCESS' " +
            "   AND valid = 1 " +
            "   GROUP BY raw_id" +
            ") latest ON latest.raw_id = r.raw_id AND latest.latest_batch_id = r.batch_id " +
            "WHERE r.issue_key = #{issueKey} " +
            "AND r.status = 'SUCCESS' " +
            "AND r.valid = 1")
    List<AiParseResultRecord> queryLatestSuccessByIssueAll(@Param("issueKey") String issueKey);

    /**
     * 查询指定 rawId 集合在当前期次下最新有效批次的全部成功解析记录
     */
    @Select("<script>" +
            "SELECT r.* " +
            "FROM t_ai_parse_result r " +
            "INNER JOIN (" +
            "   SELECT raw_id, MAX(batch_id) AS latest_batch_id " +
            "   FROM t_ai_parse_result " +
            "   WHERE issue_key = #{issueKey} " +
            "   AND status = 'SUCCESS' " +
            "   AND valid = 1 " +
            "   AND raw_id IN " +
            "   <foreach collection='rawIds' item='rawId' open='(' separator=',' close=')'>" +
            "       #{rawId}" +
            "   </foreach> " +
            "   GROUP BY raw_id" +
            ") latest ON latest.raw_id = r.raw_id AND latest.latest_batch_id = r.batch_id " +
            "WHERE r.issue_key = #{issueKey} " +
            "AND r.status = 'SUCCESS' " +
            "AND r.valid = 1 " +
            "AND r.raw_id IN " +
            "<foreach collection='rawIds' item='rawId' open='(' separator=',' close=')'>" +
            "    #{rawId}" +
            "</foreach>" +
            "</script>")
    List<AiParseResultRecord> queryLatestSuccessByIssueAndRawIds(@Param("issueKey") String issueKey,
                                                                 @Param("rawIds") List<Long> rawIds);

    @Select("SELECT latest.raw_id " +
            "FROM (" +
            "   SELECT raw_id, MAX(batch_id) AS latest_batch_id " +
            "   FROM t_ai_parse_result " +
            "   WHERE issue_key = #{issueKey} " +
            "   AND status = 'SUCCESS' " +
            "   AND valid = 1 " +
            "   GROUP BY raw_id" +
            ") latest")
    List<Long> queryLatestSuccessRawIdsByIssue(@Param("issueKey") String issueKey);

    @Select("SELECT DISTINCT r.raw_id " +
            "FROM t_ai_parse_result r " +
            "INNER JOIN (" +
            "   SELECT raw_id, MAX(batch_id) AS latest_batch_id " +
            "   FROM t_ai_parse_result " +
            "   WHERE issue_key = #{issueKey} " +
            "   AND status = 'SUCCESS' " +
            "   AND valid = 1 " +
            "   GROUP BY raw_id" +
            ") latest ON latest.raw_id = r.raw_id AND latest.latest_batch_id = r.batch_id " +
            "WHERE r.issue_key = #{issueKey} " +
            "AND r.status = 'SUCCESS' " +
            "AND r.valid = 1 " +
            "AND r.numbers LIKE CONCAT('%\\\"', #{betNumber}, '\\\"%')")
    List<Long> queryLatestSuccessRawIdsByIssueAndBetNumber(@Param("issueKey") String issueKey,
                                                           @Param("betNumber") String betNumber);

    @Select("SELECT COUNT(*) FROM t_ai_parse_result " +
            "WHERE issue_key = #{issueKey} " +
            "AND category = #{category} " +
            "AND game = #{game} " +
            "AND status = 'SUCCESS' " +
            "AND valid = 1")
    int countSuccessByIssue(@Param("issueKey") String issueKey,
                            @Param("category") String category,
                            @Param("game") String game);

    @Select("SELECT COUNT(DISTINCT r.id) " +
            "FROM t_order_raw r " +
            "JOIN t_order_parse_batch b ON b.raw_id = r.id AND b.is_effective = 1 AND b.parse_status = 1 " +
            "JOIN t_ai_parse_result a ON a.raw_id = r.id AND a.batch_id = b.id " +
            "WHERE r.received_at >= #{start} AND r.received_at < #{end} " +
            "AND r.source = 'WECHAT_REDIS' " +
            "AND a.issue_key = #{issueKey} " +
            "AND a.status = 'SUCCESS' AND a.valid = 1 " +
            "AND (r.raw_text IS NULL OR r.raw_text NOT REGEXP '[0-9]{3}') " +
            "AND (r.raw_text IS NULL OR r.raw_text NOT REGEXP '豹子|三批|三匹|组三|组六')")
    int countSuspiciousSuccessWithoutExplicitThreeDigit(@Param("issueKey") String issueKey,
                                                        @Param("start") LocalDateTime start,
                                                        @Param("end") LocalDateTime end);
}
