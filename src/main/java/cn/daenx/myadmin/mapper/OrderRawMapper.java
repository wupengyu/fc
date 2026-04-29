package cn.daenx.myadmin.mapper;

import cn.daenx.myadmin.entity.OrderRaw;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface OrderRawMapper extends BaseMapper<OrderRaw> {

    @Select("<script>" +
            "SELECT COUNT(*) " +
            "FROM t_order_raw " +
            "WHERE id IN " +
            "<foreach collection='rawIds' item='rawId' open='(' separator=',' close=')'>" +
            "    #{rawId}" +
            "</foreach> " +
            "<if test='messageKeyword != null'> " +
            "AND raw_text LIKE CONCAT('%', #{messageKeyword}, '%') " +
            "</if>" +
            "</script>")
    int countByRawIdsAndKeyword(@Param("rawIds") List<Long> rawIds,
                                @Param("messageKeyword") String messageKeyword);

    @Select("<script>" +
            "SELECT * " +
            "FROM t_order_raw " +
            "WHERE id IN " +
            "<foreach collection='rawIds' item='rawId' open='(' separator=',' close=')'>" +
            "    #{rawId}" +
            "</foreach> " +
            "<if test='messageKeyword != null'> " +
            "AND raw_text LIKE CONCAT('%', #{messageKeyword}, '%') " +
            "</if> " +
            "ORDER BY received_at DESC, id DESC " +
            "LIMIT #{offset}, #{pageSize}" +
            "</script>")
    List<OrderRaw> queryPageByRawIdsAndKeyword(@Param("rawIds") List<Long> rawIds,
                                               @Param("messageKeyword") String messageKeyword,
                                               @Param("offset") int offset,
                                               @Param("pageSize") int pageSize);
}
