package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.domain.entity.EmsCompany;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsUserProfileMapper;
import com.witos.ems.server.service.EmsBusinessConfigService;
import com.witos.ems.server.service.EmsCompanyService;
import com.witos.ems.server.service.EmsPriceService;
import com.witos.ems.server.support.EmsPageSupport;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EmsCompanyServiceImpl implements EmsCompanyService
{
    @Resource
    private EmsCompanyMapper companyMapper;

    @Resource
    private EmsUserProfileMapper userProfileMapper;

    @Resource
    private EmsAuthScopeService authScopeService;

    @Resource
    private EmsBusinessConfigService businessConfigService;

    @Resource
    private EmsPriceService priceService;

    @Override
    public IPage<Map<String, Object>> list(Map<String, String> query)
    {
        return companyMapper.selectCompanyPage(EmsPageSupport.page(), queryMap(query), authScopeService.currentScope());
    }

    @Override
    public List<Map<String, Object>> listAll(Map<String, String> query)
    {
        return companyMapper.selectCompanyList(queryMap(query), authScopeService.currentScope());
    }

    @Override
    public List<Map<String, Object>> tree()
    {
        List<Map<String, Object>> rows = companyMapper.selectCompanyList(new LinkedHashMap<String, Object>(), authScopeService.currentScope());
        return buildTree(rows);
    }

    @Override
    public Map<String, Object> get(Long id)
    {
        return companyMapper.selectCompanyDetail(id, authScopeService.currentScope());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Map<String, Object> body)
    {
        Long companyId = EmsRequestSupport.coalesceId(body, "companyId", "id");
        Long parentId = EmsRequestSupport.asLong(body.get("parentId"));
        if (parentId == null)
        {
            parentId = 0L;
        }
        String companyName = EmsRequestSupport.stringValue(body.get("companyName"));
        if (StringUtils.isEmpty(companyName))
        {
            throw new ServiceException("公司名称不能为空");
        }

        EmsCompany current = null;
        if (companyId != null)
        {
            Map<String, Object> currentDetail = companyMapper.selectCompanyDetail(companyId, authScopeService.currentScope());
            if (currentDetail == null || currentDetail.isEmpty())
            {
                throw new ServiceException("公司不在当前权限范围内或不存在");
            }
            current = companyMapper.selectById(companyId);
            if (current == null)
            {
                throw new ServiceException("公司不存在");
            }
        }

        EmsCompany parent = validateParent(parentId, companyId, current);
        Long tenantId = current != null ? current.getTenantId()
                : parent != null ? parent.getTenantId() : EmsRequestSupport.requestedTenantId(body);
        if (parent != null && !tenantId.equals(parent.getTenantId()))
        {
            throw new ServiceException("父公司与当前公司不属于同一租户");
        }

        EmsCompany company = new EmsCompany();
        company.setId(companyId);
        company.setTenantId(tenantId);
        company.setParentId(parentId);
        company.setAncestors(buildAncestors(parent));
        company.setCompanyName(companyName);
        company.setCompanyDesc(EmsRequestSupport.stringValue(body.get("companyDesc")));
        company.setCountry(EmsRequestSupport.stringValue(body.get("country")));
        company.setProvince(EmsRequestSupport.stringValue(body.get("province")));
        company.setCity(EmsRequestSupport.stringValue(body.get("city")));
        company.setAddress(EmsRequestSupport.stringValue(body.get("address")));
        company.setLongitude(EmsRequestSupport.asBigDecimal(body.get("longitude")));
        company.setLatitude(EmsRequestSupport.asBigDecimal(body.get("latitude")));
        company.setWebsite(EmsRequestSupport.stringValue(body.get("website")));
        company.setRecordNo(EmsRequestSupport.stringValue(body.get("recordNo")));
        company.setOrderNum(EmsRequestSupport.asInteger(body.get("orderNum"), 0));
        company.setStatus(EmsRequestSupport.defaultString(body.get("status"), "0"));
        company.setRemark(EmsRequestSupport.stringValue(body.get("remark")));

        if (companyId == null)
        {
            companyMapper.insert(company);
            companyId = company.getId();
            userProfileMapper.bindFirstCompanyToDefaultAdmin(company.getTenantId(), companyId);
            businessConfigService.bindCompanyDefaults(company.getTenantId(), companyId);
            priceService.initDefaultsForCompany(company.getTenantId(), companyId);
        }
        else
        {
            companyMapper.updateById(company);
        }
        return get(companyId);
    }

    @Override
    public boolean remove(Long id)
    {
        Map<String, Object> currentDetail = companyMapper.selectCompanyDetail(id, authScopeService.currentScope());
        if (currentDetail == null || currentDetail.isEmpty())
        {
            throw new ServiceException("公司不在当前权限范围内或不存在");
        }
        return companyMapper.deleteById(id) > 0;
    }

    private EmsCompany validateParent(Long parentId, Long companyId, EmsCompany current)
    {
        if (parentId == null || parentId == 0L)
        {
            return null;
        }
        Map<String, Object> parentDetail = companyMapper.selectCompanyDetail(parentId, authScopeService.currentScope());
        if (parentDetail == null || parentDetail.isEmpty())
        {
            throw new ServiceException("父公司不在当前权限范围内或不存在");
        }
        if (companyId != null && companyId.equals(parentId))
        {
            throw new ServiceException("父公司不能选择自身");
        }
        EmsCompany parent = companyMapper.selectById(parentId);
        if (parent == null)
        {
            throw new ServiceException("父公司不存在");
        }
        if (current != null && containsAncestor(parent.getAncestors(), companyId))
        {
            throw new ServiceException("父公司不能选择当前公司或其下级公司");
        }
        return parent;
    }

    private String buildAncestors(EmsCompany parent)
    {
        if (parent == null)
        {
            return "0";
        }
        return parent.getAncestors() + "," + parent.getId();
    }

    private boolean containsAncestor(String ancestors, Long companyId)
    {
        if (companyId == null || StringUtils.isEmpty(ancestors))
        {
            return false;
        }
        String token = String.valueOf(companyId);
        String[] parts = ancestors.split(",");
        for (String part : parts)
        {
            if (token.equals(part))
            {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> buildTree(List<Map<String, Object>> rows)
    {
        Map<Long, Map<String, Object>> nodeMap = new LinkedHashMap<Long, Map<String, Object>>();
        Map<Long, Long> parentMap = new HashMap<Long, Long>();
        for (Map<String, Object> row : rows)
        {
            Long companyId = EmsRequestSupport.asLong(row.get("companyId"));
            if (companyId == null)
            {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("id", companyId);
            node.put("label", EmsRequestSupport.stringValue(row.get("companyName")));
            node.put("children", new ArrayList<Map<String, Object>>());
            nodeMap.put(companyId, node);
            parentMap.put(companyId, EmsRequestSupport.asLong(row.get("parentId")));
        }

        List<Map<String, Object>> roots = new ArrayList<Map<String, Object>>();
        for (Map.Entry<Long, Map<String, Object>> entry : nodeMap.entrySet())
        {
            Long companyId = entry.getKey();
            Map<String, Object> node = entry.getValue();
            Long parentId = parentMap.get(companyId);
            Map<String, Object> parentNode = parentId == null ? null : nodeMap.get(parentId);
            if (parentId == null || parentId == 0L || parentNode == null)
            {
                roots.add(node);
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) parentNode.get("children");
            children.add(node);
        }
        pruneEmptyChildren(roots);
        return roots;
    }

    private void pruneEmptyChildren(List<Map<String, Object>> nodes)
    {
        for (Map<String, Object> node : nodes)
        {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
            if (children != null && !children.isEmpty())
            {
                pruneEmptyChildren(children);
            }
            else
            {
                node.put("children", null);
            }
        }
    }

    private Map<String, Object> queryMap(Map<String, String> query)
    {
        return query == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(query);
    }
}
