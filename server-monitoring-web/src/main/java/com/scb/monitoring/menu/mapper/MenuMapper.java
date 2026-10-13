package com.scb.monitoring.menu.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.scb.monitoring.menu.domain.Menu;

/** 좌측 메뉴 Mapper. SQL: resources/mapper/menu/MenuMapper.xml (ANSI SQL) */
@Mapper
public interface MenuMapper {

    /** 전체 메뉴 (부모 없는 것 먼저, 정렬 순서) */
    List<Menu> selectMenuList();

    int insertMenu(Menu menu);

    /** 일반 사용자 접근 허용 여부 */
    int updateUserYn(@Param("menuId") String menuId, @Param("userYn") String userYn, @Param("updUser") String updUser);

    /** 위치·순서·이름 */
    int updateLayout(@Param("menuId") String menuId, @Param("parentId") String parentId, @Param("sortOrd") int sortOrd,
                     @Param("menuNm") String menuNm, @Param("updUser") String updUser);

    /** 처음 위치·순서·이름·권한으로 (코드의 기본값) */
    int resetMenu(Menu menu);

    // ---- 사용자별 메뉴 권한 (v11) ----
    /** Y / N, 사용자가 없으면 null */
    String selectUserMenuCustom(@Param("userId") String userId);

    List<String> selectUserMenuIds(@Param("userId") String userId);

    int updateUserMenuCustom(@Param("userId") String userId, @Param("yn") String yn);

    int deleteUserMenus(@Param("userId") String userId);

    int insertUserMenu(@Param("userId") String userId, @Param("menuId") String menuId, @Param("regUser") String regUser);
}
