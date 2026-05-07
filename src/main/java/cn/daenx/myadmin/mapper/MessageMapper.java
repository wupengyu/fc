package cn.daenx.myadmin.mapper;

import cn.daenx.myadmin.entity.Message;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    @Select("SELECT IFNULL(source, 'NULL') AS source, COUNT(*) AS message_count " +
            "FROM t_message WHERE received_at >= #{start} AND received_at < #{end} " +
            "GROUP BY IFNULL(source, 'NULL') ORDER BY source")
    List<Map<String, Object>> countBySource(@Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);
}
