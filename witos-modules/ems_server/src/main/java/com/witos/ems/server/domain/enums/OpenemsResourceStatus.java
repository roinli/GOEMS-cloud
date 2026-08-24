package com.witos.ems.server.domain.enums;

/**
 * OpenEMS 资源与任务状态枚举集合。
 *
 * 说明：
 * - 本类只承载 T01 阶段的统一状态定义，后续服务层直接复用。
 * - 状态值保持和任务文档、设计文档一致，避免字符串散落。
 */
public final class OpenemsResourceStatus
{
    private OpenemsResourceStatus()
    {
    }

    public interface CodedStatus
    {
        String getCode();

        String getLabel();
    }

    /**
     * OpenEMS 控制器来源。
     */
    public enum EdgeSourceType implements CodedStatus
    {
        EMS_CREATED("EMS_CREATED", "EMS创建"),
        BACKEND_SYNCED("BACKEND_SYNCED", "Backend同步");

        private final String code;
        private final String label;

        EdgeSourceType(String code, String label)
        {
            this.code = code;
            this.label = label;
        }

        @Override
        public String getCode()
        {
            return this.code;
        }

        @Override
        public String getLabel()
        {
            return this.label;
        }
    }

    /**
     * OpenEMS 在线状态。
     */
    public enum OnlineStatus implements CodedStatus
    {
        ONLINE("ONLINE", "在线"),
        OFFLINE("OFFLINE", "离线"),
        MISSING("MISSING", "消失"),
        STALE("STALE", "过期");

        private final String code;
        private final String label;

        OnlineStatus(String code, String label)
        {
            this.code = code;
            this.label = label;
        }

        @Override
        public String getCode()
        {
            return this.code;
        }

        @Override
        public String getLabel()
        {
            return this.label;
        }
    }

    /**
     * OpenEMS 设备/下发状态。
     */
    public enum ProvisionState implements CodedStatus
    {
        PENDING_DISPATCH("PENDING_DISPATCH", "待下发"),
        PRECHECK("PRECHECK", "预检查"),
        PROVISIONING("PROVISIONING", "下发中"),
        VERIFYING("VERIFYING", "回查中"),
        ACTIVE("ACTIVE", "生效"),
        CONFLICT("CONFLICT", "冲突"),
        UNSUPPORTED("UNSUPPORTED", "不支持"),
        PARTIAL_FAILED("PARTIAL_FAILED", "部分失败"),
        FAILED("FAILED", "失败"),
        DISABLED("DISABLED", "停用"),
        PENDING_RECONCILIATION("PENDING_RECONCILIATION", "待核对");

        private final String code;
        private final String label;

        ProvisionState(String code, String label)
        {
            this.code = code;
            this.label = label;
        }

        @Override
        public String getCode()
        {
            return this.code;
        }

        @Override
        public String getLabel()
        {
            return this.label;
        }
    }

    /**
     * OpenEMS 创建任务状态。
     */
    public enum CreateTaskState implements CodedStatus
    {
        CREATING("CREATING", "创建中"),
        SUCCESS("SUCCESS", "成功"),
        FAILED("FAILED", "失败"),
        PENDING_RECONCILIATION("PENDING_RECONCILIATION", "待核对");

        private final String code;
        private final String label;

        CreateTaskState(String code, String label)
        {
            this.code = code;
            this.label = label;
        }

        @Override
        public String getCode()
        {
            return this.code;
        }

        @Override
        public String getLabel()
        {
            return this.label;
        }
    }

    /**
     * OpenEMS 绑定状态。
     */
    public enum BindingStatus implements CodedStatus
    {
        ACTIVE("ACTIVE", "有效"),
        EXPIRED("EXPIRED", "过期"),
        DISABLED("DISABLED", "停用");

        private final String code;
        private final String label;

        BindingStatus(String code, String label)
        {
            this.code = code;
            this.label = label;
        }

        @Override
        public String getCode()
        {
            return this.code;
        }

        @Override
        public String getLabel()
        {
            return this.label;
        }
    }

    /**
     * OpenEMS 协议模板适配状态。
     */
    public enum TemplateAdaptationStatus implements CodedStatus
    {
        ADAPTED("ADAPTED", "已适配"),
        AUTO_GENERATED("AUTO_GENERATED", "自动解析"),
        ADVANCED_JSON("ADVANCED_JSON", "高级JSON");

        private final String code;
        private final String label;

        TemplateAdaptationStatus(String code, String label)
        {
            this.code = code;
            this.label = label;
        }

        @Override
        public String getCode()
        {
            return this.code;
        }

        @Override
        public String getLabel()
        {
            return this.label;
        }
    }

    /**
     * OpenEMS 能力状态。
     */
    public enum CapabilityStatus implements CodedStatus
    {
        ACTIVE("ACTIVE", "有效"),
        STALE("STALE", "过期"),
        MISSING("MISSING", "缺失"),
        UNSUPPORTED("UNSUPPORTED", "不支持");

        private final String code;
        private final String label;

        CapabilityStatus(String code, String label)
        {
            this.code = code;
            this.label = label;
        }

        @Override
        public String getCode()
        {
            return this.code;
        }

        @Override
        public String getLabel()
        {
            return this.label;
        }
    }

    /**
     * OpenEMS 补拉任务状态。
     */
    public enum BackfillState implements CodedStatus
    {
        PENDING("PENDING", "待执行"),
        RUNNING("RUNNING", "执行中"),
        SUCCESS("SUCCESS", "成功"),
        FAILED("FAILED", "失败"),
        CANCELLED("CANCELLED", "取消");

        private final String code;
        private final String label;

        BackfillState(String code, String label)
        {
            this.code = code;
            this.label = label;
        }

        @Override
        public String getCode()
        {
            return this.code;
        }

        @Override
        public String getLabel()
        {
            return this.label;
        }
    }
}
