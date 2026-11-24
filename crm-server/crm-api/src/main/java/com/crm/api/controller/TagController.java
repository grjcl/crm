package com.crm.api.controller;

import java.util.List;

import com.crm.api.common.ApiResponse;
import com.crm.api.dto.TagDtos;
import com.crm.api.security.RequireRole;
import com.crm.api.security.Role;
import com.crm.api.service.TagService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标签字典：列表登录即可，增删改仅 ADMIN（BE-API-019~022）。
 */
@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    public ApiResponse<List<TagDtos.TagItem>> list() {
        return ApiResponse.ok(tagService.list());
    }

    @PostMapping
    @RequireRole(Role.ADMIN)
    public ApiResponse<TagDtos.CreatedTag> create(
            @RequestBody(required = false) TagDtos.NameRequest body,
            HttpServletRequest request) {
        String name = body == null ? null : body.name();
        return ApiResponse.ok(tagService.create(name, request));
    }

    @PutMapping("/{id}")
    @RequireRole(Role.ADMIN)
    public ApiResponse<TagDtos.RenamedTag> rename(
            @PathVariable Long id,
            @RequestBody(required = false) TagDtos.NameRequest body,
            HttpServletRequest request) {
        String name = body == null ? null : body.name();
        return ApiResponse.ok(tagService.rename(id, name, request));
    }

    @DeleteMapping("/{id}")
    @RequireRole(Role.ADMIN)
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        tagService.delete(id, request);
        return ApiResponse.ok();
    }
}
